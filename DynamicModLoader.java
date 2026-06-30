package ru.mymod;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import org.spongepowered.asm.mixin.Mixins;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class DynamicModLoader {

    public static int compareVersions(String v1, String v2) {
        String[] a = v1.split("\\.");
        String[] b = v2.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int num1 = i < a.length ? Integer.parseInt(a[i]) : 0;
            int num2 = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (num1 != num2) return Integer.compare(num1, num2);
        }
        return 0;
    }

    public void registerMod(File jarFile) throws Exception {
        // 1. Read fabric.mod.json from JAR
        JsonObject modJson = readModJson(jarFile);
        // 2. Add JAR to classpath
        Path modPath = jarFile.toPath();
        FabricLauncherBase.getLauncher().addToClassPath(modPath);
        // 3. Parse metadata
        LoaderModMetadata metadata = parseMetadata(jarFile, modJson);
        // 4. Create ModCandidateImpl

        String vers = (String) FabricLoaderImpl.class.getField("VERSION").get(null);

        System.out.println("[Loader] Fabric version: " + vers);

        //compareVersions("0.16.0", "0.16.1");  // -1 (меньше)
        //compareVersions("0.18.3", "0.16.2");  // 1 (больше)
        //compareVersions("0.14.6", "0.14.6");  // 0 (равно)

        int version = compareVersions("0.16.0", vers);

        Class<?> clazz;
        if (version == -1) {
            // > 0.16.0
            clazz = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
        } else {
            clazz = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidate");
        }

        Method method = clazz.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
        method.setAccessible(true);
        Object candidate = method.invoke(null,
                Collections.singletonList(modPath),
                metadata,
                false,
                Collections.emptyList()
        );

        Constructor<ModContainerImpl> constructor = ModContainerImpl.class.getDeclaredConstructor(clazz);
        constructor.setAccessible(true);

        // 5. Create ModContainerImpl
        ModContainerImpl container = constructor.newInstance(candidate);
        // 6. Add to FabricLoaderImpl via reflection
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        Field modsField = FabricLoaderImpl.class.getDeclaredField("mods");
        modsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ModContainerImpl> mods = (List<ModContainerImpl>) modsField.get(loader);
        mods.add(container);
        Field modMapField = FabricLoaderImpl.class.getDeclaredField("modMap");
        modMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) modMapField.get(loader);
        modMap.put(metadata.getId(), container);
        // 7. Register mixin configs
        for (String mixinConfig : metadata.getMixinConfigs(EnvType.CLIENT)) {
            Mixins.addConfiguration(mixinConfig);
        }
        // 8. Register entrypoints
        registerEntrypoints(loader, container, metadata);
        System.out.println("[Loader] Dynamic mod registered: " + metadata.getId());
    }

    private JsonObject readModJson(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry == null) {
                throw new FileNotFoundException("No fabric.mod.json in " + jarFile);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return new Gson().fromJson(new InputStreamReader(in), JsonObject.class);
            }
        }
    }

    private LoaderModMetadata parseMetadata(File jarFile, JsonObject json) throws Exception {
        // Build metadata using V1ModMetadata constructor via reflection
        // since it's package-private
        String id = json.get("id").getAsString();
        String versionStr = json.has("version") ? json.get("version").getAsString() : "1.0.0";
        Version version = net.fabricmc.loader.impl.util.version.VersionParser.parseSemantic(versionStr);
        Collection<String> provides = Collections.emptyList();
        if (json.has("provides")) {
            provides = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("provides")) {
                provides.add(e.getAsString());
            }
        }
        ModEnvironment environment = ModEnvironment.UNIVERSAL;
        if (json.has("environment")) {
            environment = ModEnvironment.valueOf(json.get("environment").getAsString().toUpperCase());
        }
        Map<String, List<EntrypointMetadata>> entrypoints = parseEntrypoints(json);
        Collection<Object> mixins = parseMixins(json);
        Collection<NestedJarEntry> jars = Collections.emptyList();
        if (json.has("jars")) {
            jars = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("jars")) {
                jars.add(new NestedJarEntry() {
                    final String str = e.getAsJsonObject().get("file").getAsString();

                    @Override
                    public String getFile() {
                        return str;
                    }
                });
            }

            try (JarFile jar = new JarFile(jarFile)) {
                for (NestedJarEntry entry : jars) {
                    String filePath = entry.getFile();

                    ZipEntry nestedEntry = jar.getEntry(filePath);
                    if (nestedEntry == null) continue;

                    // Извлекаем во временную папку
                    Path nestedPath = jarFile.toPath().getParent()
                            .resolve("libs")
                            .resolve(Paths.get(filePath).getFileName());

                    Files.createDirectories(nestedPath.getParent());
                    try (InputStream is = jar.getInputStream(nestedEntry)) {
                        Files.copy(is, nestedPath, StandardCopyOption.REPLACE_EXISTING);
                    }

                    FabricLauncherBase.getLauncher().addToClassPath(nestedPath);
                }
            }
        }
        String name = json.has("name") ? json.get("name").getAsString() : null;
        String description = json.has("description") ? json.get("description").getAsString() : "";
        Collection<Person> authors = Collections.emptyList();
        Collection<Person> contributors = Collections.emptyList();
        ContactInformation contact = ContactInformation.EMPTY;
        Collection<String> license = Collections.emptyList();
        Map<String, String> languageAdapters = Collections.emptyMap();
        // Use V1ModMetadata constructor via reflection
        Class<?> mixinEntryClass = Class.forName("net.fabricmc.loader.impl.metadata.V1ModMetadata$MixinEntry");
        Class<?> clazz = Class.forName("net.fabricmc.loader.impl.metadata.V1ModMetadata");
        //Map<String, List<EntrypointMetadata>> entrypoints, Collection<NestedJarEntry> jars,
        //			Collection<MixinEntry> mixins, /* @Nullable */ String classTweaker,
        //			Collection<ModDependency> dependencies, boolean hasRequires,
        //			/* @Nullable */ String name, /* @Nullable */String description,
        //			Collection<Person> authors, Collection<Person> contributors, /* @Nullable */ContactInformation contact, Collection<String> license, IconEntry icon,
        //			Map<String, String> languageAdapters,
        //			Map<String, CustomValue> customValues
        Class<?> clazz2 = Class.forName("net.fabricmc.loader.impl.metadata.V1ModMetadata$IconEntry");
        Constructor<?> constructor = clazz.getDeclaredConstructor(
                String.class, Version.class, Collection.class, ModEnvironment.class,
                Map.class, Collection.class, Collection.class, String.class,
                Collection.class, boolean.class, String.class, String.class,
                Collection.class, Collection.class, ContactInformation.class,
                Collection.class, clazz2, Map.class, Map.class
        );
        constructor.setAccessible(true);
        return (LoaderModMetadata) constructor.newInstance(
                id, version, provides, environment,
                entrypoints, jars, mixins, null,
                Collections.emptyList(), false, name, description,
                authors, contributors, contact, license,
                null, languageAdapters, Collections.emptyMap()
        );
    }

    private Map<String, List<EntrypointMetadata>> parseEntrypoints(JsonObject json) {
        Map<String, List<EntrypointMetadata>> result = new HashMap<>();
        if (!json.has("entrypoints")) return result;
        JsonObject entrypointsObj = json.getAsJsonObject("entrypoints");
        for (Map.Entry<String, JsonElement> entry : entrypointsObj.entrySet()) {
            String key = entry.getKey();
            List<EntrypointMetadata> list = new ArrayList<>();
            for (JsonElement e : entry.getValue().getAsJsonArray()) {
                String value;
                String adapter = "default";
                if (e.isJsonObject()) {
                    JsonObject obj = e.getAsJsonObject();
                    value = obj.get("value").getAsString();
                    if (obj.has("adapter")) {
                        adapter = obj.get("adapter").getAsString();
                    }
                } else {
                    value = e.getAsString();
                }
                String adapterFinal = adapter;
                String valueFinal = value;
                EntrypointMetadata metadata = new EntrypointMetadata() {
                    @Override
                    public String getAdapter() {
                        return adapterFinal;
                    }

                    @Override
                    public String getValue() {
                        return valueFinal;
                    }
                };
                list.add(metadata);
            }
            result.put(key, list);
        }
        return result;
    }

    private Collection<Object> parseMixins(JsonObject json) throws Exception {
        List<Object> result = new ArrayList<>();
        if (!json.has("mixins")) return result;
        Class<?> mixinEntryClass = Class.forName("net.fabricmc.loader.impl.metadata.V1ModMetadata$MixinEntry");
        Constructor<?> constructor = mixinEntryClass.getDeclaredConstructor(String.class, ModEnvironment.class);
        constructor.setAccessible(true);
        for (JsonElement e : json.getAsJsonArray("mixins")) {
            String config;
            String environment = null;
            if (e.isJsonObject()) {
                JsonObject obj = e.getAsJsonObject();
                config = obj.get("config").getAsString();
                if (obj.has("environment")) {
                    environment = obj.get("environment").getAsString();
                }
            } else {
                config = e.getAsString();
            }
            result.add(constructor.newInstance(config, ModEnvironment.UNIVERSAL)); // TODO parse from envirmoment string
        }
        return result;
    }

    private void registerEntrypoints(FabricLoaderImpl loader, ModContainerImpl container, LoaderModMetadata metadata) throws Exception {
        // Get entrypointStorage via reflection
        Field storageField = FabricLoaderImpl.class.getDeclaredField("entrypointStorage");
        storageField.setAccessible(true);
        EntrypointStorage storage = (EntrypointStorage) storageField.get(loader);
        // Get adapterMap via reflection
        Field adapterMapField = FabricLoaderImpl.class.getDeclaredField("adapterMap");
        adapterMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, LanguageAdapter> adapterMap = (Map<String, LanguageAdapter>) adapterMapField.get(loader);
        // Register each entrypoint
        Method addMethod = EntrypointStorage.class.getDeclaredMethod(
                "add", ModContainerImpl.class, String.class, EntrypointMetadata.class, Map.class
        );
        addMethod.setAccessible(true);
        for (String key : metadata.getEntrypointKeys()) {
            for (EntrypointMetadata entry : metadata.getEntrypoints(key)) {
                addMethod.invoke(storage, container, key, entry, adapterMap);
            }
        }
    }
}