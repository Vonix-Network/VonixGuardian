#!/usr/bin/env python3
"""W3 migration for 3 Forge cells' *Events.java: onExplosionDetonate + onRightClickBlock + cleanupContainerSnapshots.

Each Forge cell has slight variance in:
 - center coord accessor: 1.18.2/1.19.2/1.20.1 use getPosition() + Vec3 + BlockPos.containing()
 - source accessor: 1.18.2 uses getSourceMob() (LivingEntity), 1.19.2+/NeoForge use getDirectSourceEntity()
 - ev.getWorld() (1.18.2) vs ev.getLevel() (1.19.2+)
 - ev.getEntity() (1.19.2+) vs ev.getPlayer() (1.18.2)
"""
from pathlib import Path
import re

BASE = Path("/tmp/vg-w3-wt")

# For each Forge cell, replace onExplosionDetonate, onRightClickBlock, cleanupContainerSnapshots blocks.

FORGE_CELLS = [
    ("mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeEvents.java", "1.18.2"),
    ("mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeEvents.java", "1.19.2"),
    ("mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeEvents.java", "1.20.1"),
]

# STATIC FIELDS block appended after MAX_CONTAINER_SLOTS declaration
SCRATCH_BLOCK = """    /** Hard cap on per-container slots retained to keep modded mega-containers bounded. */
    private static final int MAX_CONTAINER_SLOTS = 216;
    /**
     * v1.3.0 W3: counter for cleanupContainerSnapshots amortization. Old path
     * scanned the full CONTAINER_SNAPSHOT_AT map on EVERY container open — O(n)
     * per open. Now we only run the full TTL scan every
     * {@link #CONTAINER_CLEANUP_EVERY} opens OR when the map exceeds
     * {@link #MAX_CONTAINER_SNAPSHOTS} (bounded-work fast-path eviction).
     */
    private static final java.util.concurrent.atomic.AtomicInteger CONTAINER_CLEANUP_TICK
            = new java.util.concurrent.atomic.AtomicInteger();
    /** Run cleanupContainerSnapshots' full TTL scan every N opens; power-of-two so we can mask. */
    private static final int CONTAINER_CLEANUP_EVERY = 32;

    /**
     * v1.3.0 W3: pooled scratch buffer for onExplosionDetonate capture. One
     * per server thread — reused across explosions to avoid re-allocating
     * 3× int[] + 1× String[] per detonation.
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

NEW_CLEANUP_BLOCK = """    /**
     * v1.3.0 W3: amortized cleanup. Old path scanned the full
     * {@code CONTAINER_SNAPSHOT_AT} map on every container open. Now we run
     * the TTL scan only every {@link #CONTAINER_CLEANUP_EVERY} opens, PLUS an
     * immediate fast-path eviction when the map exceeds
     * {@link #MAX_CONTAINER_SNAPSHOTS} (bounded work per open).
     */
    private static void cleanupContainerSnapshots() {
        // Fast-path: bounded eviction if we're over cap.
        while (CONTAINER_SNAPSHOT.size() > MAX_CONTAINER_SNAPSHOTS) {
            evictOldestContainerSnapshot();
        }
        // Amortized: only run the TTL scan every N opens (mask; N is power-of-two).
        int tick = CONTAINER_CLEANUP_TICK.incrementAndGet();
        if ((tick & (CONTAINER_CLEANUP_EVERY - 1)) != 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - CONTAINER_SNAPSHOT_TTL_MS;
        CONTAINER_SNAPSHOT_AT.entrySet().removeIf(e -> {
            Long ts = e.getValue();
            if (ts != null && ts >= cutoff) return false;
            UUID id = e.getKey();
            CONTAINER_SNAPSHOT.remove(id);
            CONTAINER_SNAPSHOT_POS.remove(id);
            LAST_CONTAINER_RC.remove(id);
            LAST_CONTAINER_RC_AT.remove(id);
            return true;
        });
    }
"""

OLD_CLEANUP_RE = re.compile(
    r"    private static void cleanupContainerSnapshots\(\) \{\n"
    r"        long cutoff = System\.currentTimeMillis\(\) - CONTAINER_SNAPSHOT_TTL_MS;\n"
    r"        CONTAINER_SNAPSHOT_AT\.entrySet\(\)\.removeIf\(e -> \{\n"
    r"            Long ts = e\.getValue\(\);\n"
    r"            if \(ts != null && ts >= cutoff\) return false;\n"
    r"            UUID id = e\.getKey\(\);\n"
    r"            CONTAINER_SNAPSHOT\.remove\(id\);\n"
    r"            CONTAINER_SNAPSHOT_POS\.remove\(id\);\n"
    r"            LAST_CONTAINER_RC\.remove\(id\);\n"
    r"            LAST_CONTAINER_RC_AT\.remove\(id\);\n"
    r"            return true;\n"
    r"        \}\);\n"
    r"        while \(CONTAINER_SNAPSHOT\.size\(\) > MAX_CONTAINER_SNAPSHOTS\) \{\n"
    r"            evictOldestContainerSnapshot\(\);\n"
    r"        \}\n"
    r"    \}\n",
    re.MULTILINE,
)


def make_explosion_block(mc):
    if mc == "1.18.2":
        level_accessor = "ev.getWorld()"
        source_expr = "ev.getExplosion().getSourceMob()"
        world_key_arg = "ev.getWorld()"
        center_expr = (
            "            Vec3 c = ev.getExplosion().getPosition();\n"
            "            BlockPos center = new BlockPos(c);\n"
        )
    elif mc == "1.19.2":
        level_accessor = "ev.getLevel()"
        source_expr = "ev.getExplosion().getExploder()"
        world_key_arg = "(Level) ev.getLevel()"
        center_expr = (
            "            Vec3 c = ev.getExplosion().getPosition();\n"
            "            BlockPos center = new BlockPos((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z));\n"
        )
    else:  # 1.20.1
        level_accessor = "ev.getLevel()"
        source_expr = "ev.getExplosion().getDirectSourceEntity()"
        world_key_arg = "(Level) ev.getLevel()"
        center_expr = (
            "            Vec3 c = ev.getExplosion().getPosition();\n"
            "            BlockPos center = BlockPos.containing(c);\n"
        )
    return f"""    /**
     * Explosion detonate — v1.3.0 W3: server thread does the per-pos
     * {{@code getBlockState}} + registry key lookup into pooled scratch arrays,
     * then hands the {{@link StringBuilder}} join + queue enqueue to
     * {{@link network.vonix.guardian.core.event.ExplosionJoinWorker}}. A 5,000-block
     * TNT chain no longer builds a 4 KiB {{@link StringBuilder}} on the tick;
     * the worker splits the join into chunked {{@code EXPLOSION}} rows.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate ev) {{
        try {{
            EventSubmitter s = sub();
            if (s == null) return;
            var affected = ev.getAffectedBlocks();
            if (affected == null || affected.isEmpty()) return;
            ExplosionScratch scratch = EXPLOSION_SCRATCH.get();
            int n = affected.size();
            scratch.grow(n);
            int idx = 0;
            for (BlockPos p : affected) {{
                scratch.xs[idx] = p.getX();
                scratch.ys[idx] = p.getY();
                scratch.zs[idx] = p.getZ();
                scratch.ids[idx] = blockId({level_accessor}.getBlockState(p));
                idx++;
            }}
            Entity source = {source_expr};
            Attribution attr = (source != null && ForgeBootstrap.resolver != null)
                    ? ForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
{center_expr}            String worldId = WorldKey.of({world_key_arg});
            String sourceTag = source != null ? SourceTagger.tag(source) : Sentinel.EXPLOSION;
            String actorName = attr.actorName() != null ? attr.actorName() : Sentinel.EXPLOSION;
            Guardian g = g();
            if (g == null) return;
            g.explosionJoinWorker().submit(s, attr.actorUuid(), actorName, worldId,
                    center.getX(), center.getY(), center.getZ(), sourceTag,
                    scratch.xs, scratch.ys, scratch.zs, scratch.ids, idx);
        }} catch (Throwable t) {{
            LOG.warn(Guardian.MARKER, "onExplosionDetonate failed", t);
        }}
    }}
"""


OLD_EXPLOSION_RE = re.compile(
    r"    @SubscribeEvent\n"
    r"    public static void onExplosionDetonate\(ExplosionEvent\.Detonate ev\) \{\n"
    r"(?:.*\n)*?"
    r"        \} catch \(Throwable t\) \{\n"
    r'            LOG\.warn\(Guardian\.MARKER, "onExplosionDetonate failed", t\);\n'
    r"        \}\n"
    r"    \}\n",
    re.MULTILINE,
)


def make_rightclick_block(mc):
    if mc == "1.18.2":
        player_getter = "ev.getPlayer()"
        pos_getter = "ev.getPos()"
        level_getter = "ev.getWorld()"
    else:
        player_getter = "ev.getEntity()"
        pos_getter = "ev.getPos()"
        level_getter = "ev.getLevel()"
    # Note: state.hasBlockEntity() is not available on 1.18.2's BlockState
    # (added later). Instead we use `state.getBlock() instanceof EntityBlock`
    # which is available across all four MC versions and gates the BE lookup
    # equivalently.
    return f"""    /**
     * Right-click block -&gt; CLICK if {{@code logInteractions}}; also snapshot
     * container pos for delta tracking.
     *
     * <p><b>v1.3.0 W3:</b> reads {{@link BlockState}} once and reuses it across
     * container-entity detection and CLICK submit. Old path called
     * {{@code getBlockState}}/{{@code getBlockEntity}} twice.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock ev) {{
        try {{
            Player p = {player_getter};
            if (p == null) return;
            BlockPos pos = {pos_getter};
            // v1.3.0 W3: single BlockState read reused below.
            BlockState state = {level_getter}.getBlockState(pos);
            // Container snapshot tracking (independent of logInteractions config).
            try {{
                if (p instanceof ServerPlayer sp
                        && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock) {{
                    // EntityBlock gate — cheap Block-class check rather than a
                    // chunk-BE-map hit for every RC on plain terrain.
                    BlockEntity be = {level_getter}.getBlockEntity(pos);
                    if (be instanceof Container) {{
                        LAST_CONTAINER_RC.put(sp.getUUID(), pos.immutable());
                        LAST_CONTAINER_RC_AT.put(sp.getUUID(), System.currentTimeMillis());
                    }}
                }}
            }} catch (Throwable t) {{
                LOG.warn(Guardian.MARKER, "onRightClickBlock (container snapshot) failed", t);
            }}
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            if (!c.actions().logInteractions()) return;
            s.submitClick(p.getUUID(), p.getName().getString(),
                    WorldKey.of({level_getter}),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(state), null);
        }} catch (Throwable t) {{
            LOG.warn(Guardian.MARKER, "onRightClickBlock failed", t);
        }}
    }}
"""


OLD_RIGHTCLICK_RE = re.compile(
    r"    @SubscribeEvent\n"
    r"    public static void onRightClickBlock\(PlayerInteractEvent\.RightClickBlock ev\) \{\n"
    r"(?:.*\n)*?"
    r"        \} catch \(Throwable t\) \{\n"
    r'            LOG\.warn\(Guardian\.MARKER, "onRightClickBlock failed", t\);\n'
    r"        \}\n"
    r"    \}\n",
    re.MULTILINE,
)

SCRATCH_ANCHOR = re.compile(
    r"    /\*\* Hard cap on per-container slots retained to keep modded mega-containers bounded\. \*/\n"
    r"    private static final int MAX_CONTAINER_SLOTS = 216;\n",
    re.MULTILINE,
)


for rel, mc in FORGE_CELLS:
    p = BASE / rel
    src = p.read_text()

    # 1) Insert scratch fields after MAX_CONTAINER_SLOTS.
    src2, n = SCRATCH_ANCHOR.subn(SCRATCH_BLOCK, src, count=1)
    assert n == 1, f"scratch anchor missed in {rel}"
    src = src2

    # 2) Replace onExplosionDetonate block.
    src2, n = OLD_EXPLOSION_RE.subn(make_explosion_block(mc), src, count=1)
    assert n == 1, f"explosion block missed in {rel}"
    src = src2

    # 3) Replace onRightClickBlock block.
    src2, n = OLD_RIGHTCLICK_RE.subn(make_rightclick_block(mc), src, count=1)
    assert n == 1, f"rightclick block missed in {rel}"
    src = src2

    # 4) Replace cleanupContainerSnapshots block.
    src2, n = OLD_CLEANUP_RE.subn(NEW_CLEANUP_BLOCK, src, count=1)
    assert n == 1, f"cleanup block missed in {rel}"
    src = src2

    p.write_text(src)
    print(f"OK {rel}")
