package network.vonix.threadedhorizons.platform;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Forge stand-in for FabricLoader queries used by the 1.18.2 reference.
 */
public final class LoaderHooks {
    private LoaderHooks() {
    }

    public static Path getConfigDir() {
        String override = System.getProperty("network.vonix.threadedhorizons.configDir");
        if (override != null && !override.isBlank()) {
            Path directory = Path.of(override);
            try {
                java.nio.file.Files.createDirectories(directory);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not create override config directory", exception);
            }
            return directory;
        }
        try {
            Path directory = FMLPaths.CONFIGDIR.get();
            if (directory != null) {
                return directory;
            }
        } catch (Throwable throwable) {
            // unit tests and early class load have no FML path context
        }
        Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "threadedhorizons-config");
        try {
            java.nio.file.Files.createDirectories(fallback);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create config directory", exception);
        }
        return fallback;
    }

    public static boolean isModLoaded(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            if (ModList.get() != null && ModList.get().isLoaded(id)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Mixin apply happens before ModList is populated.
        }
        try {
            var loading = FMLLoader.getLoadingModList();
            if (loading != null && loading.getModFileById(id) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // unit tests and very early class load have no FML context
        }
        return false;
    }

    /**
     * Lithium on Fabric and Canary on Forge both redirect CompoundTag HashMap allocation
     * and inject a blocking {@code getChunkOffThread} on ServerChunkCache.
     */
    public static boolean isLithiumFamilyId(String id) {
        return "lithium".equals(id) || "canary".equals(id);
    }

    public static boolean isLithiumFamilyLoaded() {
        return isModLoaded("lithium") || isModLoaded("canary");
    }

    public static boolean isClient() {
        try {
            return FMLEnvironment.dist == Dist.CLIENT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    public static Optional<String> getModVersion(String id) {
        if (ModList.get() == null) {
            return Optional.empty();
        }
        return ModList.get().getModContainerById(id)
                .map(container -> container.getModInfo().getVersion().toString());
    }
}
