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
 * Test helpers for v1.3.1 X2 entity-caused block-change dedicated mixins.
 *
 * <p>Entity mixins (EnderDragon, Ravager, SnowGolem, FallingBlockEntity,
 * LightningBolt, Silverfish) live in per-cell loader modules and reach
 * Minecraft classes directly, so we can't instantiate them under the
 * {@code core} test JVM. What we can test is the algebra the mixins
 * implement — which is thin, since each just wraps a {@code @Redirect}
 * around a mutation call and dispatches to the loader-side MixinBridge
 * only if the mutation returned {@code true}.</p>
 *
 * <p>The bridge dispatchers themselves are what actually shape the
 * {@link Action} that lands in the queue. This harness models those
 * dispatchers dependency-free (no MC classes), so regression tests can
 * pin (a) the {@link ActionType}, (b) the source-tag string, and (c)
 * the {@code actorName} sentinel that ships on the submitted row.</p>
 *
 * <p>Reserved v1.3.1 X2 sourceTag values (must match the constants in
 * every cell's {@code EntitySentinel.java}):</p>
 * <ul>
 *   <li>{@code #enderdragon}</li>
 *   <li>{@code #ravager}</li>
 *   <li>{@code #snow_golem}</li>
 *   <li>{@code #gravity}</li>
 *   <li>{@code #lightning}</li>
 *   <li>{@code #silverfish}</li>
 * </ul>
 */
public final class EntityMixinGuardHarness {
    private EntityMixinGuardHarness() {}

    public static final String SRC_ENDER_DRAGON = "#enderdragon";
    public static final String SRC_RAVAGER      = "#ravager";
    public static final String SRC_SNOW_GOLEM   = "#snow_golem";
    public static final String SRC_GRAVITY      = "#gravity";
    public static final String SRC_LIGHTNING    = "#lightning";
    public static final String SRC_SILVERFISH   = "#silverfish";

    /**
     * Models the {@code entityBreak} dispatcher shared by both Fabric and
     * NeoForge bridges. Produces {@link ActionType#ENTITY_CHANGE_BLOCK}
     * with {@code newBlockId="minecraft:air"} — used by EnderDragon,
     * Ravager, and FallingBlockEntity fall-side.
     *
     * <p>Guard mirrors the mixin {@code if (changed && !oldState.isAir())}
     * shape.</p>
     */
    public static boolean guardedEntityBreak(EventSubmitter s,
                                             String actorSentinel,
                                             String worldId, int x, int y, int z,
                                             String oldBlockId, boolean oldWasAir,
                                             String sourceTag,
                                             boolean changed) {
        if (changed && oldBlockId != null && !oldWasAir) {
            s.submitEntityChangeBlock(null, actorSentinel, worldId, x, y, z,
                    oldBlockId, "minecraft:air", sourceTag);
        }
        return changed;
    }

    /**
     * Models the {@code entityPlace} dispatcher — used by SnowGolem trail,
     * FallingBlockEntity land, and LightningBolt fire spawn. Produces
     * {@link ActionType#ENTITY_CHANGE_BLOCK} with {@code oldBlockId="minecraft:air"}.
     */
    public static boolean guardedEntityPlace(EventSubmitter s,
                                             String actorSentinel,
                                             String worldId, int x, int y, int z,
                                             String newBlockId, boolean newWasAir,
                                             String sourceTag,
                                             boolean changed) {
        if (changed && newBlockId != null && !newWasAir) {
            s.submitEntityChangeBlock(null, actorSentinel, worldId, x, y, z,
                    "minecraft:air", newBlockId, sourceTag);
        }
        return changed;
    }

    /**
     * Models the {@code entityChange} dispatcher — used by Silverfish infest
     * (stone → infested_stone). Full old→new state change; neither side is
     * air, so the {@code !isAir} guard collapses to a null check.
     */
    public static boolean guardedEntityChange(EventSubmitter s,
                                              String actorSentinel,
                                              String worldId, int x, int y, int z,
                                              String oldBlockId, String newBlockId,
                                              String sourceTag,
                                              boolean changed) {
        if (changed && oldBlockId != null && newBlockId != null) {
            s.submitEntityChangeBlock(null, actorSentinel, worldId, x, y, z,
                    oldBlockId, newBlockId, sourceTag);
        }
        return changed;
    }

    /**
     * Recording no-op {@link EventSubmitter} that captures every
     * {@code submitEntityChangeBlock} call. Other methods are counted but
     * not captured — mirrors {@link FireGuardHarness.Recording}.
     */
    public static final class Recording implements EventSubmitter {
        public final AtomicInteger entityChangeCount = new AtomicInteger();
        public final AtomicInteger totalCount = new AtomicInteger();
        public final List<Captured> entityChanges = new ArrayList<>();

        public static final class Captured {
            public final String actorName;
            public final String worldId;
            public final int x, y, z;
            public final String oldBlockId;
            public final String newBlockId;
            public final String sourceTag;
            public Captured(String actorName, String worldId, int x, int y, int z,
                            String oldBlockId, String newBlockId, String sourceTag) {
                this.actorName = actorName;
                this.worldId = worldId;
                this.x = x; this.y = y; this.z = z;
                this.oldBlockId = oldBlockId;
                this.newBlockId = newBlockId;
                this.sourceTag = sourceTag;
            }
        }

        @Override public void submit(Action a) { totalCount.incrementAndGet(); }

        @Override public void submitEntityChangeBlock(UUID actorUuid, String actorName, String worldId,
                                                      int x, int y, int z,
                                                      String oldBlockId, String newBlockId, String sourceTag) {
            entityChangeCount.incrementAndGet();
            totalCount.incrementAndGet();
            entityChanges.add(new Captured(actorName, worldId, x, y, z, oldBlockId, newBlockId, sourceTag));
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
        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitDispense(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitPistonExtend(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitPistonRetract(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketEmpty(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketFill(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
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
    }
}
