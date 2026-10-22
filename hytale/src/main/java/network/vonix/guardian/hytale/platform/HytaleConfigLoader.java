/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.platform;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Loads {@link GuardianConfig} from the Hytale plugin data directory.
 *
 * <p>The file is {@code <dataDir>/config.json}, where {@code dataDir} is
 * {@link PluginBase#getDataDirectory()}. A missing file is created from
 * {@link GuardianConfig#defaults()} by {@link ConfigLoader} and that fact is
 * logged (path only). An existing file that cannot be read, parsed, or
 * validated fails the load. This class never substitutes defaults for a bad
 * file.
 *
 * <p>Logs contain the data-directory path and the exception class name.
 * Config values, including database credentials, are not written to the log,
 * and parser messages are not chained as exception causes because they can
 * quote the file.
 */
public final class HytaleConfigLoader {

    public static final String FILE_NAME = "config.json";

    private HytaleConfigLoader() {
        // utility
    }

    /**
     * Create the data directory if needed and load {@code config.json}.
     *
     * @param plugin live plugin (never {@code null})
     * @return the data directory, the config path, and the validated config
     * @throws Failure when the directory cannot be created or the file is
     *     unreadable, malformed, or invalid
     */
    public static Loaded load(PluginBase plugin) throws Failure {
        if (plugin == null) {
            throw new Failure(null, null, "Plugin is null; cannot resolve the data directory", null);
        }
        HytaleLogger logger = logger(plugin);
        Path dataDir = plugin.getDataDirectory();
        if (dataDir == null) {
            String message = "Plugin data directory is null";
            logger.at(Level.SEVERE).log("%s", message);
            throw new Failure(null, null, message, null);
        }
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw fail(logger, dataDir, null,
                    "Cannot create plugin data directory " + dataDir, e);
        }

        Path configFile = dataDir.resolve(FILE_NAME);
        if (Files.exists(configFile) && !Files.isRegularFile(configFile)) {
            String message = "Guardian config path is not a file: " + configFile;
            logger.at(Level.SEVERE).log("%s", message);
            throw new Failure(dataDir, configFile, message, null);
        }

        boolean missing = !Files.exists(configFile);
        GuardianConfig config;
        try {
            config = ConfigLoader.load(configFile);
        } catch (Throwable e) {
            throw fail(logger, dataDir, configFile,
                    "Unreadable Guardian config at " + configFile
                            + "; refusing to substitute defaults", e);
        }
        if (missing) {
            logger.at(Level.INFO).log(
                    "Guardian config %s was not present; wrote defaults", configFile);
        } else {
            logger.at(Level.INFO).log("Loaded Guardian config %s", configFile);
        }
        return new Loaded(dataDir, configFile, config);
    }

    private static Failure fail(HytaleLogger logger, Path dataDir, Path configFile,
                                String message, Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        logger.at(Level.SEVERE).log("%s (%s)", message, type);
        return new Failure(dataDir, configFile, message + " (" + type + ")", sanitized(cause));
    }

    /**
     * Stack trace of {@code cause} without its message. Parser and validation
     * messages can quote {@code config.json}, so they stay off the log.
     */
    private static Throwable sanitized(Throwable cause) {
        if (cause == null) {
            return null;
        }
        IllegalStateException sanitized = new IllegalStateException(cause.getClass().getName());
        sanitized.setStackTrace(cause.getStackTrace());
        return sanitized;
    }

    private static HytaleLogger logger(PluginBase plugin) {
        HytaleLogger logger = plugin.getLogger();
        if (logger != null) {
            return logger;
        }
        return HytaleLogger.get(HytaleConfigLoader.class.getName());
    }

    /**
     * Successful load. {@link #toString()} names the paths and omits
     * {@link #config()} so a log of this object cannot print credentials.
     */
    public static final class Loaded {
        private final Path dataDir;
        private final Path configFile;
        private final GuardianConfig config;

        Loaded(Path dataDir, Path configFile, GuardianConfig config) {
            this.dataDir = dataDir;
            this.configFile = configFile;
            this.config = config;
        }

        public Path dataDir() {
            return dataDir;
        }

        public Path configFile() {
            return configFile;
        }

        public GuardianConfig config() {
            return config;
        }

        @Override
        public String toString() {
            return "Loaded{dataDir=" + dataDir + ", configFile=" + configFile + "}";
        }
    }

    /** Clear load failure. The message names a path and an exception class. */
    public static final class Failure extends Exception {
        private final Path dataDir;
        private final Path configFile;

        Failure(Path dataDir, Path configFile, String message, Throwable cause) {
            super(message, cause);
            this.dataDir = dataDir;
            this.configFile = configFile;
        }

        public Path dataDir() {
            return dataDir;
        }

        public Path configFile() {
            return configFile;
        }
    }
}
