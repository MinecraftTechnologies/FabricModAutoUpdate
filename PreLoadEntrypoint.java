package ru.mymod;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.File;
import java.nio.file.Path;

public class PreLoadEntrypoint implements PreLaunchEntrypoint {

    private final File directory;
    private final File modFile;

    public PreLoadEntrypoint() {
        Path path = FabricLoader.getInstance().getGameDir();

        this.directory = new File(path.toFile(), "mymoddir");
        this.modFile = new File(this.directory, "mymodjar.jar");

        if (!this.directory.exists() && !this.directory.mkdir()) {
            System.err.println("Couldn't create directory: " + this.directory.getAbsolutePath());
            System.exit(0);
        }
    }

    @Override
    public void onPreLaunch() {
        try {
            //
            // place here your downloading code
            // works with fabric loader version >=0.14.0
            //

            new DynamicModLoader().registerMod(modFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
