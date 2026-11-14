package network.vonix.guardian.core.action;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, by calling the public submission surface and reading the rows back,
 * that every {@link ActionType} is <em>reachable</em> — or is deliberately not.
 *
 * <p>The defect this closes: an {@code ActionType} constant can be declared,
 * documented in {@code /vg help}, accepted by the query parser and by the
 * rollback engine, and still never be produced by any code path. The rows then
 * never exist, so lookups return nothing and the type looks like a permissions
 * problem. Reading the source for a {@code submitXxx} call cannot prove the row
 * is produced — the call may be unreachable, gated off, or the type may be
 * dropped by the gate. This test asserts the observable end state instead:
 * submit through the real {@link Guardian} facade, then read the persisted
 * {@code ActionType} back out of the DAO.</p>
 *
 * <p>Nothing here pins source text. Every producer is invoked; every assertion
 * is about rows that reached storage.</p>
 *
 * <p>Deliberate absences are declared in {@link #DELIBERATELY_NOT_PRODUCED}
 * with the reason. A new enum constant that no producer emits fails this test
 * until it is either wired or documented with a reason, which is the point:
 * the alternative is a silently dead token in the lookup language.</p>
 */
class ActionTypeProducerCoverageTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String ACTOR_NAME = "Alice";
    private static final String WORLD = "minecraft:overworld";

    private static final Executor SYNC = Runnable::run;
    private static final OpLevelFallback ZERO_OP = uuid -> 0;
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) { return true; }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String i, int a, String m) { return true; }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String i, int a) { return true; }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String e, String m) { return true; }
    };

    /**
     * Types that no producer emits on purpose, with the reason an operator or
     * the next maintainer needs. Seeded so an unexplained silence is a test
     * failure rather than a surprise in production.
     */
    private static final Map<ActionType, String> DELIBERATELY_NOT_PRODUCED = Map.of(
            ActionType.CHUNK_POPULATE,
            "Chunk generation is not an attributable act: no actor, no single position, "
                    + "and the rollback engine refuses chunk-scale reverts. The token and the "
                    + "rollback read path are kept so historical rows and a:populate lookups "
                    + "still parse, but nothing writes it."
    );

    @TempDir
    Path tmp;

    private Guardian guardian;
    /** Positions already used, so the entity-change coalescer cannot merge two producers. */
    private final int[] nextX = {0};

    @BeforeEach
    void boot() throws Exception {
        guardian = Guardian.boot(everythingOn(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, daemonThreads());
    }

    @AfterEach
    void shutdown() {
        if (guardian != null) {
            guardian.close();
            guardian = null;
        }
    }

    @Test
    void everyActionTypeIsReachableThroughThePublicSubmitterOrDocumentedAsNot() throws Exception {
        Set<ActionType> produced = produceEveryType();
        guardian.awaitQueueDrained(TimeUnit.SECONDS.toMillis(10));

        Set<ActionType> persisted = persistedTypes();
        List<ActionType> neverStored = new ArrayList<>();
        for (ActionType type : ActionType.values()) {
            if (DELIBERATELY_NOT_PRODUCED.containsKey(type)) {
                continue;
            }
            if (!persisted.contains(type)) {
                neverStored.add(type);
            }
        }
        assertThat(neverStored)
                .as("submitted but never persisted — the producer is dead, gated off, "
                        + "or the row was dropped after submission")
                .isEmpty();

        EnumSet<ActionType> unexplained = EnumSet.noneOf(ActionType.class);
        for (ActionType type : ActionType.values()) {
            if (!produced.contains(type) && !DELIBERATELY_NOT_PRODUCED.containsKey(type)) {
                unexplained.add(type);
            }
        }
        assertThat(unexplained)
                .as("ActionType has neither a producer nor a documented reason to be silent")
                .isEmpty();
    }

    @Test
    void theDocumentedAbsencesAreExactlyTheTypesNothingProduces() throws Exception {
        Set<ActionType> produced = produceEveryType();
        guardian.awaitQueueDrained(TimeUnit.SECONDS.toMillis(10));

        EnumSet<ActionType> silent = EnumSet.allOf(ActionType.class);
        silent.removeAll(produced);

        assertThat(silent)
                .as("the set of types no producer emits changed; if a type was just wired, "
                        + "drop it from DELIBERATELY_NOT_PRODUCED; if a producer was removed, "
                        + "add it with a reason")
                .containsExactlyInAnyOrderElementsOf(DELIBERATELY_NOT_PRODUCED.keySet());
    }

    @Test
    void everyDocumentedAbsenceStatesItsReason() {
        assertThat(DELIBERATELY_NOT_PRODUCED).isNotEmpty();
        for (Map.Entry<ActionType, String> e : DELIBERATELY_NOT_PRODUCED.entrySet()) {
            assertThat(e.getValue())
                    .as("%s must explain why it is never produced", e.getKey())
                    .isNotBlank()
                    .hasSizeGreaterThan(40);
        }
    }

    // ------------------------------------------------------------------ producers

    /**
     * Invokes every producer the core submission surface exposes and returns the
     * types it emitted. Each call uses its own coordinates so per-position
     * coalescing cannot hide a row.
     */
    private Set<ActionType> produceEveryType() {
        EnumSet<ActionType> emitted = EnumSet.noneOf(ActionType.class);
        emitters.forEach((type, emit) -> {
            emit.run();
            emitted.add(type);
        });
        return emitted;
    }

    private final Map<ActionType, Runnable> emitters = new LinkedHashMap<>();

    {
        emitters.put(ActionType.BLOCK_BREAK, () ->
                guardian.submitBlockBreak(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:stone", null));
        emitters.put(ActionType.BLOCK_PLACE, () ->
                guardian.submitBlockPlace(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:stone", null));
        emitters.put(ActionType.CONTAINER_DEPOSIT, () ->
                guardian.submitContainerChange(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:diamond", 3, null));
        emitters.put(ActionType.CONTAINER_WITHDRAW, () ->
                guardian.submitContainerChange(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:diamond", -3, null));
        emitters.put(ActionType.ITEM_DROP, () ->
                guardian.submitItemDrop(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:diamond", 2, null));
        emitters.put(ActionType.ITEM_PICKUP, () ->
                guardian.submitItemPickup(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:diamond", 2, null));
        emitters.put(ActionType.ENTITY_KILL, () ->
                guardian.submitEntityKill(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:pig", null));
        emitters.put(ActionType.EXPLOSION, () ->
                guardian.submitExplosion(null, "#creeper", WORLD, at(), 64, 0, "0,64,0:minecraft:stone", "explosion:creeper"));
        emitters.put(ActionType.CHAT, () ->
                guardian.submitChat(ACTOR, ACTOR_NAME, WORLD, "hello"));
        emitters.put(ActionType.COMMAND, () ->
                guardian.submitCommand(ACTOR, ACTOR_NAME, WORLD, "/vg lookup r:5"));
        emitters.put(ActionType.SIGN, () ->
                guardian.submitSign(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "line one\nline two"));
        emitters.put(ActionType.SESSION_JOIN, () ->
                guardian.submitSessionJoin(ACTOR, ACTOR_NAME, WORLD, "127.0.0.1"));
        emitters.put(ActionType.SESSION_LEAVE, () ->
                guardian.submitSessionLeave(ACTOR, ACTOR_NAME, WORLD, "quit"));
        emitters.put(ActionType.USERNAME_CHANGE, () ->
                guardian.submitUsernameChange(ACTOR, "Alice2", WORLD, "Alice"));
        emitters.put(ActionType.BURN, () ->
                guardian.submitBurn(null, "#fire", WORLD, at(), 64, 0, "minecraft:oak_planks", "#fire"));
        emitters.put(ActionType.IGNITE, () ->
                guardian.submitIgnite(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:fire", null));
        emitters.put(ActionType.FADE, () ->
                guardian.submitFade(null, "#natural", WORLD, at(), 64, 0, "minecraft:ice", "#natural"));
        // 2.3.0 additions: each has a producer on every loader cell.
        emitters.put(ActionType.BLOCK_TRANSFORM, () ->
                guardian.submitBlockTransform(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:oak_log", null));
        emitters.put(ActionType.FARMLAND_TRAMPLE, () ->
                guardian.submitFarmlandTrample(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:farmland", null));
        emitters.put(ActionType.REDSTONE_TOGGLE, () ->
                guardian.submitRedstoneToggle(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:lever", null));
        emitters.put(ActionType.FORM, () ->
                guardian.submitForm(null, "#natural", WORLD, at(), 64, 0, "minecraft:snow", "#natural"));
        emitters.put(ActionType.SPREAD, () ->
                guardian.submitSpread(null, "#natural", WORLD, at(), 64, 0, "minecraft:grass_block", "#natural"));
        emitters.put(ActionType.DISPENSE, () ->
                guardian.submitDispense(null, "#dispenser", WORLD, at(), 64, 0, "minecraft:arrow", "#dispenser"));
        emitters.put(ActionType.PISTON_EXTEND, () ->
                guardian.submitPistonExtend(null, "#piston", WORLD, at(), 64, 0, "minecraft:piston_head", null));
        emitters.put(ActionType.PISTON_RETRACT, () ->
                guardian.submitPistonRetract(null, "#piston", WORLD, at(), 64, 0, "minecraft:air", null));
        emitters.put(ActionType.BUCKET_EMPTY, () ->
                guardian.submitBucketEmpty(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:water", null));
        emitters.put(ActionType.BUCKET_FILL, () ->
                guardian.submitBucketFill(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:water", null));
        emitters.put(ActionType.LEAVES_DECAY, () ->
                guardian.submitLeavesDecay(null, "#natural", WORLD, at(), 64, 0, "minecraft:oak_leaves", "#natural"));
        emitters.put(ActionType.ENTITY_CHANGE_BLOCK, () ->
                guardian.submitEntityChangeBlock(null, "#mob:minecraft:ravager", WORLD,
                        at(), 64, 0, "minecraft:wheat", "minecraft:air", "#mob:minecraft:ravager"));
        emitters.put(ActionType.INVENTORY_DEPOSIT, () ->
                guardian.submitInventoryDeposit(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:apple", 1, null));
        emitters.put(ActionType.INVENTORY_WITHDRAW, () ->
                guardian.submitInventoryWithdraw(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:apple", 1, null));
        emitters.put(ActionType.HOPPER_PUSH, () ->
                guardian.submitHopperPush(null, "#hopper", WORLD, at(), 64, 0, "minecraft:iron_ingot", 1, "#hopper"));
        emitters.put(ActionType.HOPPER_PULL, () ->
                guardian.submitHopperPull(null, "#hopper", WORLD, at(), 64, 0, "minecraft:iron_ingot", 1, "#hopper"));
        emitters.put(ActionType.ITEM_CRAFT, () ->
                guardian.submitItemCraft(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:crafting_table", 1, null));
        emitters.put(ActionType.ENTITY_SPAWN, () ->
                guardian.submitEntitySpawn(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:pig", null));
        emitters.put(ActionType.ENTITY_INTERACT, () ->
                guardian.submitEntityInteract(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:villager", null));
        emitters.put(ActionType.HANGING_PLACE, () ->
                guardian.submitHangingPlace(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:item_frame", null));
        emitters.put(ActionType.HANGING_BREAK, () ->
                guardian.submitHangingBreak(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:item_frame", null));
        emitters.put(ActionType.STRUCTURE_GROW, () ->
                guardian.submitStructureGrow(null, "#natural", WORLD, at(), 64, 0, "minecraft:oak", "#natural"));
        emitters.put(ActionType.PORTAL_CREATE, () ->
                guardian.submitPortalCreate(null, "#portal", WORLD, at(), 64, 0, "minecraft:nether_portal", "#portal"));
        emitters.put(ActionType.FLUID_FLOW, () ->
                guardian.submitFluidFlow(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:water", null));
        emitters.put(ActionType.CLICK, () ->
                guardian.submitClick(ACTOR, ACTOR_NAME, WORLD, at(), 64, 0, "minecraft:oak_door", null));
    }

    /** A position nobody else used, so the entity-change coalescer keeps every row. */
    private int at() {
        return nextX[0]++;
    }

    private Set<ActionType> persistedTypes() throws Exception {
        Set<ActionType> types = EnumSet.noneOf(ActionType.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Action> rows = guardian.dao().query(QueryFilter.empty(), 0, 500);
            types.clear();
            for (Action row : rows) {
                types.add(row.type());
            }
            if (types.containsAll(reachableTypes())) {
                return types;
            }
            Thread.sleep(20L);
        }
        return types;
    }

    /** Every type this test expects to observe in storage. */
    private static Set<ActionType> reachableTypes() {
        EnumSet<ActionType> reachable = EnumSet.allOf(ActionType.class);
        reachable.removeAll(DELIBERATELY_NOT_PRODUCED.keySet());
        return reachable;
    }

    // -------------------------------------------------------------- fixtures

    /**
     * Every per-type toggle that can drop a row during this test is turned on,
     * and the entity-change allowlist is opened. Without this the test would
     * measure the shipped defaults (water flow off, modded entity changes off)
     * rather than whether a producer exists.
     */
    private static GuardianConfig everythingOn(Path dataDir) {
        GuardianConfig d = GuardianConfig.defaults();
        GuardianConfig.Actions a = d.actions();
        GuardianConfig.Actions open = new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                a.worldBlacklist(), List.of(), a.sourceBlacklist(),
                a.itemBlacklist(), a.entityBlacklist(),
                a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(),
                a.entityChangeAllowlist(), true,
                true, true, true, true, true, true,
                true, true,
                true, true, false, true, false,
                true);
        return new GuardianConfig(
                new GuardianConfig.Database("sqlite",
                        dataDir.resolve("producer-coverage.db").toAbsolutePath().toString(),
                        null, null, null),
                new GuardianConfig.Queue(50_000, 25L, 1_000),
                new GuardianConfig.LogFile(false, "logs/vonixguardian", false, 30, false),
                open, d.permissions(), d.lookup(), d.privacy(), d.purge(),
                new GuardianConfig.Storage(false), d.rollback(), d.theme(), d.language());
    }

    private static ThreadFactory daemonThreads() {
        return r -> {
            Thread t = new Thread(r, "vg-producer-coverage-test");
            t.setDaemon(true);
            return t;
        };
    }
}
