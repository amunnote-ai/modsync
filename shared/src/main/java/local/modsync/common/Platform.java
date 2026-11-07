package local.modsync.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * The few things shared code needs from the mod loader. The loader entry point (Forge or NeoForge)
 * calls {@link #init} before anything else runs.
 */
public final class Platform {
    public static final Logger LOGGER = LoggerFactory.getLogger("ModSync");

    private static Path gameDir;
    private static Path modsDir;
    private static Path configDir;

    public static void init(Path gameDir, Path modsDir, Path configDir) {
        Platform.gameDir = gameDir;
        Platform.modsDir = modsDir;
        Platform.configDir = configDir;
    }

    public static Path gameDir() {
        return gameDir;
    }

    public static Path modsDir() {
        return modsDir;
    }

    public static Path configDir() {
        return configDir;
    }
}
