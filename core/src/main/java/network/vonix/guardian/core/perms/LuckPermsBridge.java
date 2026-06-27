package network.vonix.guardian.core.perms;

import java.lang.reflect.Method;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure-reflection bridge to the LuckPerms API.
 *
 * <p>This class MUST NEVER {@code import net.luckperms.*}. LuckPerms is a soft dependency: the core
 * jar compiles and runs without it, and every interaction goes through {@link Class#forName} +
 * {@link Method#invoke}. See {@code references/consuming-arr-mod-api-as-soft-dep.md} in the
 * forge-mod-maintenance-fork skill for the rationale.
 *
 * <p>Reflective call chain:
 *
 * <pre>
 *   net.luckperms.api.LuckPermsProvider.get()        -&gt; LuckPerms
 *       .getUserManager()                            -&gt; UserManager
 *       .getUser(UUID)                               -&gt; User (nullable)
 *       .getCachedData()                             -&gt; CachedDataManager
 *       .getPermissionData()                         -&gt; CachedPermissionData
 *       .checkPermission(String)                     -&gt; Tristate { TRUE, FALSE, UNDEFINED }
 * </pre>
 *
 * <p>Failure semantics:
 *
 * <ul>
 *   <li>{@link ClassNotFoundException} / {@link NoClassDefFoundError} — LuckPerms is not on the
 *       classpath. The resolver caches this as permanently unavailable.
 *   <li>{@link IllegalStateException} — LuckPerms is present but its API has not yet been
 *       registered (server still starting). Transient; the resolver retries on the next call.
 *   <li>{@link NullPointerException} — defensive: an internal NPE during reflection. Treated as
 *       transient.
 *   <li>Anything else — logged at debug; treated as transient {@link TristateResult#UNDEFINED}.
 * </ul>
 */
public final class LuckPermsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(LuckPermsBridge.class);

    private static final String PROVIDER_CLASS = "net.luckperms.api.LuckPermsProvider";

    /** Tri-state result mirroring {@code net.luckperms.api.util.Tristate}. */
    public enum TristateResult {
        TRUE,
        FALSE,
        UNDEFINED
    }

    private LuckPermsBridge() {}

    /**
     * Probe for LuckPerms presence.
     *
     * @return {@code true} if {@link #PROVIDER_CLASS} can be loaded
     * @throws NoClassDefFoundError if a transitive class is missing (callers should treat this as
     *     permanently unavailable)
     */
    public static boolean isAvailable() {
        try {
            Class.forName(PROVIDER_CLASS, false, LuckPermsBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Reflectively check a permission node for a user.
     *
     * @param uuid player UUID
     * @param node permission node
     * @return tri-state result; {@link TristateResult#UNDEFINED} if LP has no opinion, the user is
     *     unknown, or any transient/unexpected reflective failure occurs
     * @throws NoClassDefFoundError if LuckPerms classes are missing at link time
     * @throws ClassNotFoundException if {@link #PROVIDER_CLASS} is not on the classpath
     * @throws IllegalStateException if LuckPerms is loaded but its API is not yet registered
     */
    public static TristateResult checkPermission(UUID uuid, String node)
            throws ClassNotFoundException, IllegalStateException {
        if (uuid == null || node == null || node.isEmpty()) {
            return TristateResult.UNDEFINED;
        }
        try {
            Class<?> providerClass =
                    Class.forName(PROVIDER_CLASS, true, LuckPermsBridge.class.getClassLoader());
            Method getMethod = providerClass.getMethod("get");
            Object luckPerms = getMethod.invoke(null); // throws IllegalStateException if not loaded

            if (luckPerms == null) {
                return TristateResult.UNDEFINED;
            }

            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            if (userManager == null) {
                return TristateResult.UNDEFINED;
            }

            Method getUser = userManager.getClass().getMethod("getUser", UUID.class);
            Object user = getUser.invoke(userManager, uuid);
            if (user == null) {
                // User not loaded (offline / not yet cached) — LP cannot answer.
                return TristateResult.UNDEFINED;
            }

            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            if (cachedData == null) {
                return TristateResult.UNDEFINED;
            }

            Object permissionData =
                    cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
            if (permissionData == null) {
                return TristateResult.UNDEFINED;
            }

            Object tristate =
                    permissionData
                            .getClass()
                            .getMethod("checkPermission", String.class)
                            .invoke(permissionData, node);
            if (tristate == null) {
                return TristateResult.UNDEFINED;
            }

            return mapTristate(tristate);
        } catch (ClassNotFoundException e) {
            throw e; // resolver caches permanently
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof IllegalStateException ise) {
                throw ise; // resolver retries
            }
            if (cause instanceof NoClassDefFoundError ncdf) {
                throw ncdf; // resolver caches permanently
            }
            LOGGER.debug("LuckPerms reflective invocation failed", cause != null ? cause : ite);
            return TristateResult.UNDEFINED;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            LOGGER.debug("LuckPerms reflective access failed; API shape may have changed", e);
            return TristateResult.UNDEFINED;
        } catch (NullPointerException e) {
            // Defensive: treat as transient.
            LOGGER.debug("Unexpected NPE while reflecting into LuckPerms", e);
            return TristateResult.UNDEFINED;
        }
    }

    /**
     * Map a reflective {@code net.luckperms.api.util.Tristate} instance to {@link TristateResult}
     * by enum name. Falls back to {@link TristateResult#UNDEFINED} when the name is unrecognised.
     */
    static TristateResult mapTristate(Object tristate) {
        if (tristate == null) {
            return TristateResult.UNDEFINED;
        }
        try {
            // Tristate is an enum; name() is universal.
            String name = ((Enum<?>) tristate).name();
            return switch (name) {
                case "TRUE" -> TristateResult.TRUE;
                case "FALSE" -> TristateResult.FALSE;
                default -> TristateResult.UNDEFINED;
            };
        } catch (ClassCastException e) {
            // Some shaded API copies expose Tristate as non-enum; fall back to toString().
            String s = String.valueOf(tristate);
            if ("TRUE".equalsIgnoreCase(s)) return TristateResult.TRUE;
            if ("FALSE".equalsIgnoreCase(s)) return TristateResult.FALSE;
            return TristateResult.UNDEFINED;
        }
    }
}
