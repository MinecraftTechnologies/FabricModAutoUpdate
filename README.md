# Fabric Mod Auto Update

Позволяет загрузить скачанный мод на этапе запуска майнкрафта. По сути можно скачать мод прямо при запуске и загрузить. Идеально подходит для автоматического обновления мода.

Проверено на версиях Fabric Loader от 0.14.0 до 0.19.3, java 8.

## Правильное использование

Необходимо создать отдельный мод-загрузчик, который будет скачивать и загружать в игру основной мод. В этот мод загрузчик необходимо поместить код из репозитория и вызвать его на этапе PreLaunchEntrypoint (не позже) и обязательно в синхронном потоке. Скачивание тоже должно происходит синхронно.

# Fabric Mod Auto Update (English)

Load the downloaded mod during the Minecraft startup process. Essentially, the mod can be downloaded right as the game launches and loads. This is ideal for automatic mod updates.

Tested with Fabric Loader versions 0.14.0 through 0.19.3 and Java 8.

## Right usage

Create loader mod to download and load the main mod into the game. Within this loader, the mod code must be moved from the repository and invoked at the `PreLaunchEntrypoint` stage (not post), strictly on a synchronous thread. The download process must also be synchronous.
