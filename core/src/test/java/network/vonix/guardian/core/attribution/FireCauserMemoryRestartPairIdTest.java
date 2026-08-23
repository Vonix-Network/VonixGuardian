/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restart-collision regression for generated fire/break pairing tokens.
 *
 * <p>A process-local {@code AtomicLong} starting at zero reissues
 * {@code 1, 2, 3…} after {@code FireCauserMemory} is reconstructed. Durable
 * {@code findByPairIds} equality lookup would then pull unrelated historical
 * siblings. These tests exercise two fresh instances as pre-restart and
 * post-restart allocators.
 */
class FireCauserMemoryRestartPairIdTest {

    private static final String WORLD = "minecraft:overworld";
    private static final int SAMPLE = 8_192;
    private static final long[] EXPLICIT_PAIR_IDS = {42L, 77L, 99L, 100L, 200L};

    private static FireCauserMemory mem(AtomicLong clock, int maxEntries) {
        return new FireCauserMemory(60_000L, maxEntries, 0, clock::get);
    }

    private static FireCauserMemory.CauserRecord provisional(UUID actor, long now) {
        return FireCauserMemory.CauserRecord.allowlisted(
                actor, "Dragon", "mod:dragon", "#entity", now, now);
    }

    @Test
    void freshInstancesAfterRestartDoNotReissueGeneratedPairIds() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        UUID actor = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        FireCauserMemory preRestart = mem(clock, SAMPLE + 16);
        FireCauserMemory postRestart = mem(clock, SAMPLE + 16);

        Set<Long> preIds = allocateGenerated(preRestart, actor, clock.get(), SAMPLE, 0);
        Set<Long> postIds = allocateGenerated(postRestart, actor, clock.get(), SAMPLE, 0);

        assertThat(preIds).hasSize(SAMPLE).doesNotContain(0L, clock.get());
        assertThat(postIds).hasSize(SAMPLE).doesNotContain(0L, clock.get());
        assertThat(java.util.Collections.disjoint(preIds, postIds))
                .as("post-restart generated tokens must not collide with pre-restart tokens")
                .isTrue();
    }

    @Test
    void repeatedGeneratedIdsFromOneInstanceRemainDistinct() {
        AtomicLong clock = new AtomicLong(5_000L);
        FireCauserMemory mem = mem(clock, SAMPLE + 16);
        UUID actor = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Set<Long> ids = allocateGenerated(mem, actor, clock.get(), SAMPLE, 0);
        assertThat(ids).hasSize(SAMPLE).doesNotContain(0L, 5_000L);
    }

    @Test
    void explicitNonTimestampPairIdsRemainUnchanged() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock, 32);
        UUID actor = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        for (int i = 0; i < EXPLICIT_PAIR_IDS.length; i++) {
            long pairId = EXPLICIT_PAIR_IDS[i];
            mem.record(WORLD, i, 64, 0,
                    FireCauserMemory.CauserRecord.allowlisted(
                            actor, "Explicit", "mod:dragon", "#entity", pairId, clock.get()));
            assertThat(mem.pairIdAt(WORLD, i, 64, 0)).isEqualTo(pairId);
        }
    }

    @Test
    void unpairedZeroSemanticsArePreservedForSuppressedRecords() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock, 8);
        mem.record(WORLD, 0, 70, 0,
                FireCauserMemory.CauserRecord.suppressed("mod:wild", clock.get()));
        FireCauserMemory.CauserRecord r = mem.consume(WORLD, 0, 70, 0);
        assertThat(r).isNotNull();
        assertThat(r.allowlisted).isFalse();
        assertThat(r.pairId).isEqualTo(0L);
        assertThat(mem.pairIdAt(WORLD, 0, 70, 0)).isNull();
    }

    @Test
    void allowlistedZeroProvisionalIsReplacedWithNonzeroToken() {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = mem(clock, 8);
        mem.record(WORLD, 3, 64, 3,
                FireCauserMemory.CauserRecord.allowlisted(
                        UUID.randomUUID(), "A", "mod:dragon", "#entity", 0L, clock.get()));
        Long generated = mem.pairIdAt(WORLD, 3, 64, 3);
        assertThat(generated).isNotNull().isNotEqualTo(0L).isNotEqualTo(1_000L);
    }

    @Test
    void concurrentGeneratedIdsOnOneInstanceRemainDistinctAndNonzero() throws Exception {
        AtomicLong clock = new AtomicLong(9_000L);
        int perThread = 1_024;
        int threads = 8;
        FireCauserMemory mem = mem(clock, (perThread * threads) + 16);
        UUID actor = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> firstErr = new AtomicReference<>();
        Long[][] collected = new Long[threads][perThread];

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int x = seed * perThread + i;
                        mem.record(WORLD, x, 64, 0, provisional(actor, clock.get()));
                        collected[seed][i] = mem.pairIdAt(WORLD, x, 64, 0);
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "pair-id-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("generated pairId allocation must not deadlock")
                .isTrue();
        assertThat(firstErr.get()).isNull();

        Set<Long> ids = new HashSet<>();
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                Long id = collected[t][i];
                assertThat(id).isNotNull().isNotEqualTo(0L).isNotEqualTo(clock.get());
                assertThat(ids.add(id)).as("duplicate generated pairId %s", id).isTrue();
            }
        }
        assertThat(ids).hasSize(threads * perThread);
    }

    @Test
    void generatedTokensDoNotSelectUnrelatedHistoricalSiblingsAfterReopen(@TempDir Path tmp)
            throws Exception {
        Path db = tmp.resolve("guardian.db");
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        UUID preActor = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID postActor = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String preName = "PreDragon";
        String postName = "PostDragon";

        FireCauserMemory preRestart = mem(clock, 8);
        preRestart.record(WORLD, 10, 64, 20, provisional(preActor, clock.get()));
        Long prePairBoxed = preRestart.pairIdAt(WORLD, 10, 64, 20);
        assertThat(prePairBoxed).isNotNull().isNotEqualTo(0L).isNotEqualTo(clock.get());
        long prePair = prePairBoxed;

        SqliteDao dao = new SqliteDao("jdbc:sqlite:" + db);
        dao.init();
        dao.insertBatch(List.of(
                action(1_700_000_000_000L, ActionType.ENTITY_CHANGE_BLOCK, preActor, preName,
                        10, 64, 20, "minecraft:oak_log", "minecraft:air", prePair),
                action(1_700_000_000_050L, ActionType.IGNITE, preActor, preName,
                        11, 64, 20, "minecraft:fire", null, prePair)
        ));
        dao.close();

        FireCauserMemory postRestart = mem(clock, 8);
        postRestart.record(WORLD, 30, 64, 40, provisional(postActor, clock.get()));
        Long postPairBoxed = postRestart.pairIdAt(WORLD, 30, 64, 40);
        assertThat(postPairBoxed).isNotNull().isNotEqualTo(prePair).isNotEqualTo(0L);
        long postPair = postPairBoxed;

        dao = new SqliteDao("jdbc:sqlite:" + db);
        dao.init();
        dao.insertBatch(List.of(
                action(1_700_000_100_000L, ActionType.ENTITY_CHANGE_BLOCK, postActor, postName,
                        30, 64, 40, "minecraft:oak_log", "minecraft:air", postPair),
                action(1_700_000_100_050L, ActionType.IGNITE, postActor, postName,
                        31, 64, 40, "minecraft:fire", null, postPair)
        ));

        List<Action> postSiblings = dao.findByPairIds(List.of(postPair));
        assertThat(postSiblings).extracting(Action::actorUuid)
                .containsOnly(postActor);
        assertThat(postSiblings).extracting(Action::pairId)
                .containsOnly(postPair);
        assertThat(postSiblings).hasSize(2);

        List<Action> preSiblings = dao.findByPairIds(List.of(prePair));
        assertThat(preSiblings).extracting(Action::actorUuid)
                .containsOnly(preActor);
        assertThat(preSiblings).hasSize(2);
        dao.close();
    }

    private static Set<Long> allocateGenerated(FireCauserMemory mem, UUID actor,
                                               long now, int count, int xOffset) {
        Set<Long> ids = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            mem.record(WORLD, xOffset + i, 64, 0, provisional(actor, now));
            Long id = mem.pairIdAt(WORLD, xOffset + i, 64, 0);
            assertThat(id).isNotNull();
            ids.add(id);
        }
        return ids;
    }

    private static Action action(long ts, ActionType type, UUID actor, String actorName,
                                 int x, int y, int z, String target, String meta, long pairId) {
        return new Action(-1L, ts, type, actor, actorName, WORLD,
                x, y, z, target, meta, 1, false,
                type == ActionType.IGNITE ? "entity:#entity" : "#entity",
                null, null, null, null, null, null, null, null, pairId);
    }
}
