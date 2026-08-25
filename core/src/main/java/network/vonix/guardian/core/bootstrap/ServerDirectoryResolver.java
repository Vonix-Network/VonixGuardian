package network.vonix.guardian.core.bootstrap;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader-safe Minecraft server-directory resolution.
 *
 * <p>Forge 1.18.2–1.20.1 compile against Mojmap {@code getServerDirectory()}
 * returning {@code File}, while SRG runtimes expose {@code m_129843_} and
 * Sinytra Connector may remap the return type to {@code Path}. A silent cwd
 * fallback is rejected: launchers whose working directory is not the server
 * root would otherwise point config, database, quarantine, and logs at the
 * wrong tree.
 */
public final class ServerDirectoryResolver {

    /** Known Mojmap / SRG / intermediary names for {@code MinecraftServer#getServerDirectory()} across 1.18.2–1.20.1. */
    static final String[] SERVER_DIRECTORY_METHODS = {
            "getServerDirectory",
            "m_129843_",
            "m_129932_",
            "m_203581_"
    };

    private ServerDirectoryResolver() {
    }

    /**
     * Resolve the server data directory.
     *
     * @param server         MinecraftServer instance; methods are probed by name
     * @param loaderGameDir  loader-provided game directory (Forge/NeoForge
     *                       {@code FMLPaths.GAMEDIR.get()}); used when the
     *                       server object has no mapped directory method
     * @return absolute normalized path
     * @throws IllegalStateException if neither source yields a directory
     */
    public static Path resolve(Object server, Path loaderGameDir) {
        Path fromServer = tryServerDirectory(server);
        if (fromServer != null) {
            return fromServer.toAbsolutePath().normalize();
        }
        if (loaderGameDir != null) {
            return loaderGameDir.toAbsolutePath().normalize();
        }
        throw new IllegalStateException(
                "Cannot resolve server directory: MinecraftServer.getServerDirectory "
                        + "(Mojmap/SRG) was missing and no loader game directory was supplied. "
                        + "Refusing cwd fallback.");
    }

    static Path tryServerDirectory(Object server) {
        if (server == null) {
            return null;
        }
        for (String name : SERVER_DIRECTORY_METHODS) {
            Path resolved = invokeDirectoryMethod(server, name);
            if (resolved != null) {
                return resolved;
            }
        }
        for (Method method : directoryCandidateMethods(server.getClass())) {
            Path resolved = invoke(server, method);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static List<Method> directoryCandidateMethods(Class<?> type) {
        List<Method> matches = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName().toLowerCase();
            if (!name.contains("directory") && !name.contains("gamedir")) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            if (File.class.isAssignableFrom(returned) || Path.class.isAssignableFrom(returned)) {
                matches.add(method);
            }
        }
        return matches;
    }

    private static Path invokeDirectoryMethod(Object server, String name) {
        Class<?> type = server.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(name);
                return invoke(server, method);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        try {
            Method method = server.getClass().getMethod(name);
            return invoke(server, method);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Path invoke(Object server, Method method) {
        try {
            method.setAccessible(true);
            return toPath(method.invoke(server));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    static Path toPath(Object value) {
        if (value instanceof Path path) {
            return path;
        }
        if (value instanceof File file) {
            return file.toPath();
        }
        return null;
    }
}
