/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.EventSubmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test helpers for v1.3.0 W1a mixin-perf regression tests.
 *
 * <p>The Fire/Spread/Leaves mixins live in per-cell loader modules and reach
 * Minecraft classes directly, so we can't instantiate them under the {@code core}
 * test JVM. What we <em>can</em> test is the algebra those mixins implement:
 * "only submit BURN/IGNITE when the underlying {@code removeBlock}/{@code setBlock}
 * actually mutated state". This helper codifies that predicate as a small,
 * dependency-free static function and exposes a recording
 * {@link EventSubmitter} so tests can assert on submit counts + captured args.</p>
 */
public final class FireGuardHarness {
    private FireGuardHarness() {}

    /**
     * Models the guarded {@code @Redirect} handler used by all 8 FireBlockMixin
     * cells for the {@code removeBlock} INVOKE inside {@code tick} / {@code checkBurnOut}.
     *
     * <p>Returns the passed-through {@code changed} value so the surrounding
     * bytecode continues to work correctly. Only submits BURN when the block
     * was actually removed and its prior state was not air — matching the
     * production mixin body byte-for-byte in intent.</p>
     */
    public static boolean guardedRemoveBurn(EventSubmitter s,
                                            String worldId, int x, int y, int z,
                                            String oldBlockId, boolean oldWasAir,
                                            boolean changed) {
        if (changed && oldBlockId != null && !oldWasAir) {
            s.submitBurn(null, "#fire", worldId, x, y, z, oldBlockId, "world:burn");
        }
        return changed;
    }

    /**
     * Models the guarded {@code @Redirect} handler used by all 8 FireBlockMixin
     * cells for the {@code setBlock} INVOKE inside {@code tick} / {@code checkBurnOut}.
     */
    public static boolean guardedSet(EventSubmitter s,
                                     String worldId, int x, int y, int z,
                                     String oldBlockId, boolean oldWasAir,
                                     String newBlockId, boolean newIsAir,
                                     boolean sameBlock,
                                     boolean changed) {
        if (changed && newBlockId != null && oldBlockId != null && !sameBlock) {
            if (newIsAir) {
                s.submitBurn(null, "#fire", worldId, x, y, z, oldBlockId, "world:burn");
            } else {
                s.submitIgnite(null, "#fire", worldId, x, y, z, newBlockId, "world:ignite");
            }
        }
        return changed;
    }

    /**
     * Models the guarded {@code onPlace} TAIL inject used by all 8 cells:
     * submit IGNITE iff the new block differs from oldState AND the level
     * actually observes the new block (fire wasn't rejected downstream).
     */
    public static void guardedOnPlace(EventSubmitter s,
                                      String worldId, int x, int y, int z,
                                      String newBlockId,
                                      boolean oldStateSameBlock,
                                      boolean levelShowsNew) {
        if (oldStateSameBlock) return;
        if (!levelShowsNew) return;
        s.submitIgnite(null, "#fire", worldId, x, y, z, newBlockId, "world:ignite");
    }

    /** Recording no-op {@link EventSubmitter} used by regression tests. */
    public static final class Recording implements EventSubmitter {
        public final AtomicInteger burnCount = new AtomicInteger();
        public final AtomicInteger igniteCount = new AtomicInteger();
        public final List<String> burnBlockIds = new ArrayList<>();
        public final List<String> igniteBlockIds = new ArrayList<>();
        public final AtomicInteger totalCount = new AtomicInteger();

        @Override public void submit(Action a) { totalCount.incrementAndGet(); }
        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            burnCount.incrementAndGet();
            burnBlockIds.add(b);
            totalCount.incrementAndGet();
        }
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            igniteCount.incrementAndGet();
            igniteBlockIds.add(b);
            totalCount.incrementAndGet();
        }
        // ---- all other EventSubmitter methods are no-ops for these tests ----
        @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitContainerChange(UUID u, String n, String w, int x, int y, int z, String i, int d, String s) {}
        @Override public void submitItemDrop(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemPickup(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntityKill(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitExplosion(UUID u, String n, String w, int x, int y, int z, String j, String s) {}
        @Override public void submitChat(UUID u, String n, String w, String m) {}
        @Override public void submitCommand(UUID u, String n, String w, String cmd) {}
        @Override public void submitSign(UUID u, String n, String w, int x, int y, int z, String j) {}
        @Override public void submitSessionJoin(UUID u, String n, String w, String i) {}
        @Override public void submitSessionLeave(UUID u, String n, String w, String r) {}
        @Override public void submitUsernameChange(UUID u, String nn, String w, String on) {}
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitDispense(UUID u, String n, String w, int x, int y, int z, String i, String s) {}
        @Override public void submitPistonExtend(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitPistonRetract(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketEmpty(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketFill(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitEntityChangeBlock(UUID u, String n, String w, int x, int y, int z, String o, String nb, String s) {}
        @Override public void submitInventoryDeposit(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitInventoryWithdraw(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntitySpawn(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntityInteract(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
        @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}

        public int burnFor(ActionType ignored) { return burnCount.get(); }
    }
}
