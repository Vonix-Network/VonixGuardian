package network.vonix.guardian.core.query;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection-only bridge to WorldEdit for resolving a player's current
 * selection into an integer bounding box. WorldEdit is a soft dependency —
 * this class MUST NOT hold any compile-time reference to any
 * {@code com.sk89q.worldedit.*} type.
 *
 * <p>All lookups are cached after first success; every failure path
 * (WE not installed, no session, no selection, unsupported region type,
 * reflection surface change) collapses to {@link Optional#empty()} without
 * throwing.
 *
 * <p>Return value is a {@link Box} — a plain integer AABB in the player's
 * current world, min-inclusive / max-inclusive.
 */
public final class WorldEditRegionResolver {

    /** Integer-coord AABB. Both corners are inclusive. */
    public record Box(int minX, int minY, int minZ,
                      int maxX, int maxY, int maxZ) {}

    // Reflection cache — populated on first successful use.
    private static volatile Class<?> worldEditCls;
    private static volatile Method   getInstanceMth;
    private static volatile Method   getSessionManagerMth;
    private static volatile Method   findByNameMth;
    private static volatile Method   getSelectionMth;    // LocalSession#getSelection(World) OR getSelection() zero-arg
    private static volatile Method   getSelectionWorldMth; // LocalSession#getSelectionWorld()
    private static volatile Method   getMinPtMth;
    private static volatile Method   getMaxPtMth;
    private static volatile Method   vectorGetX;
    private static volatile Method   vectorGetY;
    private static volatile Method   vectorGetZ;

    private static final AtomicBoolean UNAVAILABLE = new AtomicBoolean(false);

    // Name lookup can be delegated: normally we only have UUID. Consumers
    // that already know the name pass it directly.
    /** Nullable in-process supplier for name-from-UUID (installed by platform bootstrap). */
    private static volatile NameResolver nameResolver;

    /** Functional resolver of UUID → player name (nullable if unknown/offline). */
    @FunctionalInterface
    public interface NameResolver {
        String nameOf(UUID uuid);
    }

    private WorldEditRegionResolver() {}

    /** Install a UUID→name resolver (platform bootstrap wires this once). */
    public static void setNameResolver(NameResolver r) {
        nameResolver = r;
    }

    /**
     * Resolve the given player's WorldEdit selection as an integer AABB.
     *
     * @return {@link Optional#empty()} on any failure (WE not installed, no
     *         session for player, no selection, unsupported region type,
     *         reflection failure)
     */
    public static Optional<Box> resolveSelection(UUID player) {
        if (player == null) return Optional.empty();
        if (UNAVAILABLE.get()) return Optional.empty();
        String name = null;
        NameResolver nr = nameResolver;
        if (nr != null) {
            try {
                name = nr.nameOf(player);
            } catch (Throwable ignored) {
                // resolver blew up — treat as unknown name
            }
        }
        return resolveByName(name);
    }

    /**
     * Resolve by player name (WorldEdit's own key). Exposed for platforms
     * that already know the display name (avoids installing a resolver).
     */
    public static Optional<Box> resolveByName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        if (UNAVAILABLE.get()) return Optional.empty();
        try {
            ensureInit();
            Object we = getInstanceMth.invoke(null);
            Object sessionMgr = getSessionManagerMth.invoke(we);
            Object session = findByNameMth.invoke(sessionMgr, name);
            if (session == null) return Optional.empty();
            // getSelection may throw IncompleteRegionException — swallow to empty.
            Object region;
            Object world = null;
            if (getSelectionWorldMth != null) {
                try { world = getSelectionWorldMth.invoke(session); } catch (Throwable ignored) {}
            }
            try {
                if (getSelectionMth.getParameterCount() == 0) {
                    region = getSelectionMth.invoke(session);
                } else {
                    region = getSelectionMth.invoke(session, world);
                }
            } catch (Throwable ex) {
                return Optional.empty(); // IncompleteRegionException etc
            }
            if (region == null) return Optional.empty();
            resolveRegionMethods(region.getClass());
            Object min = getMinPtMth.invoke(region);
            Object max = getMaxPtMth.invoke(region);
            resolveVectorMethods(min.getClass());
            int minX = ((Number) vectorGetX.invoke(min)).intValue();
            int minY = ((Number) vectorGetY.invoke(min)).intValue();
            int minZ = ((Number) vectorGetZ.invoke(min)).intValue();
            int maxX = ((Number) vectorGetX.invoke(max)).intValue();
            int maxY = ((Number) vectorGetY.invoke(max)).intValue();
            int maxZ = ((Number) vectorGetZ.invoke(max)).intValue();
            return Optional.of(new Box(minX, minY, minZ, maxX, maxY, maxZ));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            UNAVAILABLE.set(true);
            return Optional.empty();
        } catch (Throwable t) {
            // Reflective failure, IllegalState (no selection), etc.
            return Optional.empty();
        }
    }

    /** @return {@code true} if WorldEdit's entry class resolves at runtime. */
    public static boolean isAvailable() {
        if (UNAVAILABLE.get()) return false;
        try {
            ensureInit();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Test hook — resets the reflection cache. */
    static void resetForTests() {
        worldEditCls = null;
        getInstanceMth = null;
        getSessionManagerMth = null;
        findByNameMth = null;
        getSelectionMth = null;
        getSelectionWorldMth = null;
        getMinPtMth = null;
        getMaxPtMth = null;
        vectorGetX = null;
        vectorGetY = null;
        vectorGetZ = null;
        UNAVAILABLE.set(false);
    }

    // ------------------------------------------------------------------

    private static void ensureInit() throws Exception {
        if (worldEditCls != null && getSelectionMth != null) return;
        Class<?> we = Class.forName("com.sk89q.worldedit.WorldEdit");
        Method getInst = we.getMethod("getInstance");
        Object weInstance = getInst.invoke(null);
        Method smMth = we.getMethod("getSessionManager");
        Object sm = smMth.invoke(weInstance);
        Method findMth = sm.getClass().getMethod("findByName", String.class);
        // LocalSession — figure out getSelection signature. Prefer zero-arg
        // (>= 7.3.0), else fall back to (World) form.
        Class<?> localSession = Class.forName("com.sk89q.worldedit.LocalSession");
        Method selMth = null;
        try {
            selMth = localSession.getMethod("getSelection");
        } catch (NoSuchMethodException ignored) {
            // older 7.x: getSelection(World)
            for (Method m : localSession.getMethods()) {
                if (m.getName().equals("getSelection") && m.getParameterCount() == 1) {
                    selMth = m; break;
                }
            }
        }
        if (selMth == null) throw new NoSuchMethodException("LocalSession.getSelection");
        Method selWorldMth = null;
        try {
            selWorldMth = localSession.getMethod("getSelectionWorld");
        } catch (NoSuchMethodException ignored) {
            selWorldMth = null;
        }
        worldEditCls = we;
        getInstanceMth = getInst;
        getSessionManagerMth = smMth;
        findByNameMth = findMth;
        getSelectionMth = selMth;
        getSelectionWorldMth = selWorldMth;
    }

    private static void resolveRegionMethods(Class<?> regionCls) throws NoSuchMethodException {
        if (getMinPtMth != null && getMaxPtMth != null
            && getMinPtMth.getDeclaringClass().isAssignableFrom(regionCls)) {
            return;
        }
        getMinPtMth = regionCls.getMethod("getMinimumPoint");
        getMaxPtMth = regionCls.getMethod("getMaximumPoint");
    }

    private static void resolveVectorMethods(Class<?> vecCls) throws NoSuchMethodException {
        if (vectorGetX != null && vectorGetX.getDeclaringClass().isAssignableFrom(vecCls)) {
            return;
        }
        // BlockVector3 (modern) exposes getX/Y/Z or x/y/z. Try both.
        Method x = tryGet(vecCls, "getX");
        if (x == null) x = tryGet(vecCls, "getBlockX");
        if (x == null) x = vecCls.getMethod("x");
        Method y = tryGet(vecCls, "getY");
        if (y == null) y = tryGet(vecCls, "getBlockY");
        if (y == null) y = vecCls.getMethod("y");
        Method z = tryGet(vecCls, "getZ");
        if (z == null) z = tryGet(vecCls, "getBlockZ");
        if (z == null) z = vecCls.getMethod("z");
        vectorGetX = x;
        vectorGetY = y;
        vectorGetZ = z;
    }

    private static Method tryGet(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (NoSuchMethodException e) { return null; }
    }
}
