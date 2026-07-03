#!/usr/bin/env python3
"""W3 migration for 4 Fabric cells' FabricMixinBridge.java.

Two changes per cell:
1) explosion(...) rewrites to capture arrays on server thread and hand to Guardian.explosionJoinWorker().
2) cleanupContainerSnapshots() gets amortized every-N counter (same pattern as Forge cells).

Note: BlockPos.containing(cx,cy,cz) exists in 1.20.1+; 1.18.2 uses new BlockPos(cx,cy,cz);
1.19.2 uses new BlockPos((int)Math.floor(cx), ...). Fabric variant of Guardian.explosionJoinWorker
is the same — the bridges pass the same shape.
"""
from pathlib import Path
import re

BASE = Path("/tmp/vg-w3-wt")

FABRIC_CELLS = [
    ("mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/FabricMixinBridge.java", "1.18.2"),
    ("mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/FabricMixinBridge.java", "1.19.2"),
    ("mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricMixinBridge.java", "1.20.1"),
    ("mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricMixinBridge.java", "1.21.1"),
]

# ---- 1) explosion(...) rewrite ---------------------------------------------

OLD_EXPLOSION_RE = re.compile(
    r"    /\*\* Explosion#finalizeExplosion → EXPLOSION\. Iterates affected block list\. \*/\n"
    r"    public static void explosion\(Level level, Entity source,\n"
    r"                                 double cx, double cy, double cz,\n"
    r"                                 List<BlockPos> affected\) \{\n"
    r"(?:.*\n)*?"
    r"        \} catch \(Throwable t\) \{\n"
    r'            warn\("explosion", t\);\n'
    r"        \}\n"
    r"    \}\n",
    re.MULTILINE,
)


def make_explosion(mc):
    if mc == "1.18.2":
        center_expr = "            BlockPos center = new BlockPos(cx, cy, cz);\n"
    elif mc == "1.19.2":
        center_expr = "            BlockPos center = new BlockPos((int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));\n"
    else:
        center_expr = "            BlockPos center = BlockPos.containing(cx, cy, cz);\n"
    return f"""    /**
     * Explosion#finalizeExplosion → EXPLOSION.
     *
     * <p><b>v1.3.0 W3:</b> server thread does the (unavoidable) per-pos
     * {{@code getBlockState}} + registry key lookup into pooled scratch arrays,
     * then hands the {{@link StringBuilder}} join + queue enqueue to
     * {{@link network.vonix.guardian.core.event.ExplosionJoinWorker}}. A 5,000-block
     * TNT chain no longer builds a 4 KiB {{@link StringBuilder}} on the tick.</p>
     */
    public static void explosion(Level level, Entity source,
                                 double cx, double cy, double cz,
                                 List<BlockPos> affected) {{
        try {{
            EventSubmitter s = sub();
            if (s == null || level == null || affected == null || affected.isEmpty()) return;
            ExplosionScratch scratch = EXPLOSION_SCRATCH.get();
            int n = affected.size();
            scratch.grow(n);
            int idx = 0;
            for (BlockPos p : affected) {{
                scratch.xs[idx] = p.getX();
                scratch.ys[idx] = p.getY();
                scratch.zs[idx] = p.getZ();
                scratch.ids[idx] = blockId(level.getBlockState(p));
                idx++;
            }}
            Attribution attr = (source != null && FabricBootstrap.resolver != null)
                    ? FabricBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
{center_expr}            String worldId = WorldKey.of(level);
            String sourceTag = source != null ? SourceTagger.tag(source) : Sentinel.EXPLOSION;
            String actorName = attr.actorName() != null ? attr.actorName() : Sentinel.EXPLOSION;
            network.vonix.guardian.core.Guardian g = VonixGuardianFabric.guardian();
            if (g == null) return;
            g.explosionJoinWorker().submit(s, attr.actorUuid(), actorName, worldId,
                    center.getX(), center.getY(), center.getZ(), sourceTag,
                    scratch.xs, scratch.ys, scratch.zs, scratch.ids, idx);
        }} catch (Throwable t) {{
            warn("explosion", t);
        }}
    }}
"""


# ---- 2) cleanupContainerSnapshots(...) rewrite ------------------------------

OLD_CLEANUP_RE = re.compile(
    r"    private static void cleanupContainerSnapshots\(\) \{\n"
    r"        long cutoff = System\.currentTimeMillis\(\) - SNAP_TTL_MS;\n"
    r"        SNAP_TIME\.entrySet\(\)\.removeIf\(e -> \{\n"
    r"            Long ts = e\.getValue\(\);\n"
    r"            if \(ts != null && ts >= cutoff\) return false;\n"
    r"            UUID id = e\.getKey\(\);\n"
    r"            SNAP\.remove\(id\);\n"
    r"            SNAP_POS\.remove\(id\);\n"
    r"            SNAP_WORLD\.remove\(id\);\n"
    r"            return true;\n"
    r"        \}\);\n"
    r"        while \(SNAP\.size\(\) > MAX_CONTAINER_SNAPSHOTS\) \{\n"
    r"            evictOldestContainerSnapshot\(\);\n"
    r"        \}\n"
    r"    \}\n",
    re.MULTILINE,
)

NEW_CLEANUP = """    /**
     * v1.3.0 W3: amortized cleanup. Old path scanned the full SNAP_TIME map on
     * every container open (O(n) per open). Now we run the TTL scan only every
     * {@link #CONTAINER_CLEANUP_EVERY} opens, PLUS an immediate fast-path
     * eviction when the map exceeds {@link #MAX_CONTAINER_SNAPSHOTS}
     * (bounded work per open).
     */
    private static void cleanupContainerSnapshots() {
        // Fast-path: bounded eviction if we're over cap.
        while (SNAP.size() > MAX_CONTAINER_SNAPSHOTS) {
            evictOldestContainerSnapshot();
        }
        // Amortized: only run the TTL scan every N opens (mask; N is power-of-two).
        int tick = CONTAINER_CLEANUP_TICK.incrementAndGet();
        if ((tick & (CONTAINER_CLEANUP_EVERY - 1)) != 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - SNAP_TTL_MS;
        SNAP_TIME.entrySet().removeIf(e -> {
            Long ts = e.getValue();
            if (ts != null && ts >= cutoff) return false;
            UUID id = e.getKey();
            SNAP.remove(id);
            SNAP_POS.remove(id);
            SNAP_WORLD.remove(id);
            return true;
        });
    }
"""

# ---- 3) Insert amortization counters and scratch fields after MAX_CONTAINER_SLOTS ----

SCRATCH_ANCHOR = re.compile(
    r"    private static final int MAX_CONTAINER_SLOTS = 216;\n",
    re.MULTILINE,
)

SCRATCH_BLOCK = """    private static final int MAX_CONTAINER_SLOTS = 216;

    /**
     * v1.3.0 W3: counter for cleanupContainerSnapshots amortization. Old path
     * scanned the full SNAP_TIME map on EVERY container open — O(n) per open.
     * Now we only run the TTL scan every {@link #CONTAINER_CLEANUP_EVERY} opens
     * OR when the map exceeds {@link #MAX_CONTAINER_SNAPSHOTS}.
     */
    private static final java.util.concurrent.atomic.AtomicInteger CONTAINER_CLEANUP_TICK
            = new java.util.concurrent.atomic.AtomicInteger();
    /** Full TTL scan every N opens; power-of-two so we can mask. */
    private static final int CONTAINER_CLEANUP_EVERY = 32;

    /**
     * v1.3.0 W3: pooled scratch buffer for explosion capture. One per server
     * thread — reused across explosions to avoid re-allocating 3× int[] + 1×
     * String[] per detonation.
     */
    private static final ThreadLocal<ExplosionScratch> EXPLOSION_SCRATCH =
            ThreadLocal.withInitial(ExplosionScratch::new);

    private static final class ExplosionScratch {
        int[] xs = new int[512];
        int[] ys = new int[512];
        int[] zs = new int[512];
        String[] ids = new String[512];
        void grow(int need) {
            if (xs.length >= need) return;
            int n = xs.length;
            while (n < need) n <<= 1;
            xs = new int[n]; ys = new int[n]; zs = new int[n]; ids = new String[n];
        }
    }
"""


for rel, mc in FABRIC_CELLS:
    p = BASE / rel
    src = p.read_text()

    # 1) Insert scratch fields.
    src2, n = SCRATCH_ANCHOR.subn(SCRATCH_BLOCK, src, count=1)
    assert n == 1, f"scratch anchor missed in {rel}"
    src = src2

    # 2) Replace explosion(...) impl.
    src2, n = OLD_EXPLOSION_RE.subn(make_explosion(mc), src, count=1)
    assert n == 1, f"explosion block missed in {rel}"
    src = src2

    # 3) Replace cleanupContainerSnapshots.
    src2, n = OLD_CLEANUP_RE.subn(NEW_CLEANUP, src, count=1)
    assert n == 1, f"cleanup block missed in {rel}"
    src = src2

    p.write_text(src)
    print(f"OK {rel}")
