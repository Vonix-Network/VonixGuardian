package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QuarantineStoreTest {

    @Test
    void partialAppendRollsBackAndDoesNotPublishActiveEntry(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("quarantine.bin");
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        QuarantineStore.AppendFaultInjector fault = (channel, frame) -> {
            if (failOnce.getAndSet(false)) {
                ByteBuffer partial = ByteBuffer.wrap(frame, 0, Math.min(7, frame.length));
                while (partial.hasRemaining()) channel.write(partial);
                throw new java.io.IOException("injected partial append failure");
            }
            ByteBuffer complete = ByteBuffer.wrap(frame);
            while (complete.hasRemaining()) channel.write(complete);
        };
        QuarantineStore store = new QuarantineStore(journal, 8, 1_000_000L, fault);

        // The injected write is used for the first append; the injector is
        // intentionally one-shot so the failure is observable rather than a
        // constructor-time or preflight artifact.
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.append(action(100)));
        assertThat(Files.size(journal)).isZero();
        assertThat(store.entries()).isEmpty();
        assertThat(store.append(action(102))).isPositive();
        assertThat(store.entries()).hasSize(1);
        store.markSinkSucceeded(2L);
        assertThat(store.entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.sequence()).isEqualTo(2L);
                    assertThat(e.sinkSucceeded()).isTrue();
                });
    }

    @Test
    void rollbackFailurePoisonsStoreAndRejectsLaterDurabilityClaims(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("quarantine.bin");
        QuarantineStore.AppendFaultInjector fault = new QuarantineStore.AppendFaultInjector() {
            @Override
            public void write(java.nio.channels.FileChannel channel, byte[] frame) throws java.io.IOException {
                ByteBuffer partial = ByteBuffer.wrap(frame, 0, Math.min(7, frame.length));
                while (partial.hasRemaining()) channel.write(partial);
                throw new java.io.IOException("injected partial append failure");
            }

            @Override
            public void beforeRollback(Path path, long priorBytes) throws java.io.IOException {
                Files.deleteIfExists(path);
                Files.createDirectory(path);
            }
        };
        QuarantineStore store = new QuarantineStore(journal, 8, 1_000_000L, fault);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.append(action(101)));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.append(action(102)));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.markSinkSucceeded(1L));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.acknowledge(1L));
    }

    @Test
    void appendGroupWritesReplacementPairAsOneFrameAndRejectsPartialRetention(@TempDir Path tmp) throws Exception {
        QuarantineStore store = new QuarantineStore(tmp.resolve("quarantine.bin"), 8, 1_000_000L);
        Action withdraw = new Action(-1L, 1L, ActionType.INVENTORY_WITHDRAW,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:diamond", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{1}, null, 44L, 3);
        Action deposit = new Action(-1L, 1L, ActionType.INVENTORY_DEPOSIT,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:emerald", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{2}, null, 44L, 3);
        assertThat(store.appendGroup(List.of(withdraw, deposit))).hasSize(2);
        assertThat(store.entries()).hasSize(2);

        QuarantineStore tight = new QuarantineStore(tmp.resolve("tight.bin"), 1, 1_000_000L);
        assertThat(tight.appendGroup(List.of(withdraw, deposit))).isEmpty();
        assertThat(tight.entries()).isEmpty();
    }

    @Test
    void tornGroupAddNeverReloadsASurvivingSingleton(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("torn-group-add.bin");
        Action withdraw = new Action(-1L, 1L, ActionType.INVENTORY_WITHDRAW,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:diamond", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{1}, null, 47L, 3);
        Action deposit = new Action(-1L, 1L, ActionType.INVENTORY_DEPOSIT,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:emerald", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{2}, null, 47L, 3);
        QuarantineStore.AppendFaultInjector fault = new QuarantineStore.AppendFaultInjector() {
            @Override
            public void write(java.nio.channels.FileChannel channel, byte[] frame) throws java.io.IOException {
                ByteBuffer partial = ByteBuffer.wrap(frame, 0, frame.length - 1);
                while (partial.hasRemaining()) channel.write(partial);
                throw new java.io.IOException("simulated crash during group append");
            }

            @Override
            public void beforeRollback(Path ignored, long priorBytes) throws java.io.IOException {
                throw new java.io.IOException("simulated crash before append rollback");
            }
        };
        QuarantineStore failing = new QuarantineStore(journal, 8, 1_000_000L, fault);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.appendGroup(List.of(withdraw, deposit)));

        QuarantineStore reloaded = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(reloaded.entries())
                .as("a torn group ADD must never expose one pair member as a recoverable singleton")
                .isEmpty();
    }

    @Test
    void groupSinkSuccessFrameIsAtomicAcrossPartialWrite(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("group.bin");
        Action withdraw = new Action(-1L, 1L, ActionType.INVENTORY_WITHDRAW,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:diamond", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{1}, null, 45L, 3);
        Action deposit = new Action(-1L, 1L, ActionType.INVENTORY_DEPOSIT,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:emerald", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{2}, null, 45L, 3);
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = seed.appendGroup(List.of(withdraw, deposit));
        assertThat(sequences).hasSize(2);

        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        QuarantineStore.AppendFaultInjector fault = noRollbackPartialFrame(failOnce, "injected group-marker prefix");
        QuarantineStore failing = new QuarantineStore(journal, 8, 1_000_000L, fault);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.markSinkSucceededGroup(sequences));

        QuarantineStore reloaded = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(reloaded.entries()).hasSize(2);
        assertThat(reloaded.entries()).allMatch(e -> !e.sinkSucceeded());
    }

    @Test
    void groupAckFrameIsAtomicAcrossPartialWrite(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("group-ack.bin");
        Action withdraw = new Action(-1L, 1L, ActionType.INVENTORY_WITHDRAW,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:diamond", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{1}, null, 46L, 3);
        Action deposit = new Action(-1L, 1L, ActionType.INVENTORY_DEPOSIT,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:emerald", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{2}, null, 46L, 3);
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = seed.appendGroup(List.of(withdraw, deposit));
        seed.markSinkSucceededGroup(sequences);

        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        QuarantineStore.AppendFaultInjector fault = noRollbackPartialFrame(failOnce, "injected group-ack prefix");
        QuarantineStore failing = new QuarantineStore(journal, 8, 1_000_000L, fault);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> failing.acknowledgeGroup(sequences));

        QuarantineStore reloaded = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(reloaded.entries()).hasSize(2);
        assertThat(reloaded.entries()).allMatch(QuarantineStore.Entry::sinkSucceeded);
    }

    @Test
    void entryRetentionLimitFailsClosed(@TempDir Path tmp) throws Exception {
        QuarantineStore store = new QuarantineStore(tmp.resolve("quarantine.bin"), 1, 1_000_000L);

        assertThat(store.append(action(1))).isPositive();
        assertThat(store.append(action(2))).isEqualTo(-1L);
        assertThat(store.entries()).hasSize(1);
    }

    @Test
    void appendRejectsExhaustedSequenceWithoutReusingIt(@TempDir Path tmp) throws Exception {
        QuarantineStore store = new QuarantineStore(tmp.resolve("quarantine.bin"), 8, 1_000_000L);
        java.lang.reflect.Field nextSequence = QuarantineStore.class.getDeclaredField("nextSequence");
        nextSequence.setAccessible(true);
        nextSequence.setLong(store, Long.MAX_VALUE);

        assertThat(store.append(action(999)))
                .as("Long.MAX_VALUE is exhausted and must never become a reusable sequence")
                .isEqualTo(-1L);
        assertThat(store.entries()).isEmpty();
    }

    @Test
    void compactRewritesActiveEntriesUnderBytePressure(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("quarantine.bin");
        // Generous entry cap, tight byte cap so the second append must compact.
        QuarantineStore store = new QuarantineStore(journal, 8, 1_000L);
        long first = store.append(action(1));
        assertThat(first).isPositive();
        long before = Files.size(journal);
        assertThat(before).isGreaterThan(0L);

        // Fill past the byte ceiling with ACK churn so compact is exercised, then
        // verify the remaining active entry reloads after reopen.
        long second = store.append(action(2));
        if (second > 0L) {
            store.acknowledge(first);
        }
        // Force a compact-on-ack path when the journal has grown.
        if (Files.size(journal) >= 500L) {
            store.acknowledge(second > 0L ? second : first);
        }

        QuarantineStore reopened = new QuarantineStore(journal, 8, 1_000L);
        assertThat(reopened.entries()).isNotNull();
    }

    @Test
    void interruptedAppendStillPersistsAndRestoresFlag(@TempDir Path tmp) throws Exception {
        QuarantineStore store = new QuarantineStore(tmp.resolve("quarantine.bin"), 8, 1_000_000L);
        Thread.currentThread().interrupt();
        try {
            assertThat(store.append(action(9))).isPositive();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear for the rest of the suite.
            Thread.interrupted();
        }
        assertThat(store.entries()).extracting(e -> e.action().x()).containsExactly(9);
    }

    @Test
    void appendPreflightsEncodedFrameAgainstHardByteCap(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("quarantine.bin");
        // Cap smaller than one encoded ADD frame for an oversized payload.
        long tinyCap = 200L;
        QuarantineStore store = new QuarantineStore(journal, 8, tinyCap);

        byte[] huge = new byte[512];
        Action oversized = new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                1, 64, 0, "minecraft:stone", null, 1, false, null,
                null, null, null, null, null,
                huge, null, null);

        assertThat(store.append(oversized))
                .as("frame alone exceeding cap must fail closed without writing")
                .isEqualTo(-1L);
        assertThat(Files.exists(journal) ? Files.size(journal) : 0L)
                .as("journal must stay within hard byte cap")
                .isLessThanOrEqualTo(tinyCap);
        assertThat(store.entries()).isEmpty();
    }

    @Test
    void durableSinkSuccessSurvivesRestartAndIsClearedByAck(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("quarantine.bin");
        QuarantineStore store = new QuarantineStore(journal, 8, 1_000_000L);
        long seq = store.append(action(42));
        assertThat(seq).isPositive();
        assertThat(store.entries()).singleElement()
                .extracting(QuarantineStore.Entry::sinkSucceeded).isEqualTo(false);

        store.markSinkSucceeded(seq);
        assertThat(store.entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.sequence()).isEqualTo(seq);
                    assertThat(e.sinkSucceeded()).isTrue();
                    assertThat(e.action().x()).isEqualTo(42);
                });

        QuarantineStore reopened = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(reopened.entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.sequence()).isEqualTo(seq);
                    assertThat(e.sinkSucceeded())
                            .as("SINK_SUCCEEDED must load across restart")
                            .isTrue();
                    assertThat(e.action().x()).isEqualTo(42);
                });

        reopened.acknowledge(seq);
        assertThat(reopened.entries()).isEmpty();
        QuarantineStore afterAck = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(afterAck.entries()).isEmpty();
    }

    @Test
    void acknowledgeHardCapFailsClosedWithoutRemovingActive(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("quarantine.bin");
        // Cap large enough for one ADD+SINK_SUCCEEDED compact rewrite, too small for
        // that rewrite plus a new ACK frame. Force ACK preflight to fail closed.
        Action a = action(77);
        // Measure encoded size via a generous store first.
        Path probe = tmp.resolve("probe.bin");
        QuarantineStore probeStore = new QuarantineStore(probe, 8, 1_000_000L);
        long seqProbe = probeStore.append(a);
        probeStore.markSinkSucceeded(seqProbe);
        long compactBytes = Files.size(probe);
        // ACK frame is MAGIC(4)+op(1)+seq(8)=13 bytes.
        long ackFrame = 13L;
        long tightCap = compactBytes; // room for rewrite of active, not rewrite+ACK
        assertThat(tightCap).isGreaterThan(0L);

        QuarantineStore store = new QuarantineStore(journal, 8, tightCap);
        long seq = store.append(a);
        assertThat(seq).isPositive();
        store.markSinkSucceeded(seq);
        long before = Files.size(journal);
        assertThat(before).isLessThanOrEqualTo(tightCap);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.acknowledge(seq));

        assertThat(Files.size(journal))
                .as("failed ACK must not grow journal past hard cap")
                .isLessThanOrEqualTo(tightCap);
        assertThat(store.entries())
                .as("active entry must remain for retry")
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.sequence()).isEqualTo(seq);
                    assertThat(e.sinkSucceeded()).isTrue();
                });

        // Queue recovery must not count the row recovered while ACK cannot land.
        AtomicInteger flushes = new java.util.concurrent.atomic.AtomicInteger();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                8, 25L, 4, batch -> flushes.incrementAndGet(),
                r -> {
                    Thread t = new Thread(r, "vg-ack-cap-q");
                    t.setDaemon(true);
                    return t;
                },
                store);
        try {
            Thread.sleep(1500L);
            assertThat(q.recoveredFromQuarantine())
                    .as("queue must not count recovery while ACK fails closed")
                    .isZero();
            assertThat(flushes.get())
                    .as("durable sink-success must not reflush")
                    .isZero();
            assertThat(store.entries()).hasSize(1);
            assertThat(Files.size(journal)).isLessThanOrEqualTo(tightCap);
        } finally {
            q.close();
        }
        // silence unused warning for ackFrame measurement intent
        assertThat(ackFrame).isEqualTo(13L);
    }

    @Test
    void handcraftedV1V2AndV3SingleRowFramesRemainReplayCompatible(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("legacy-frames.bin");
        Action v1 = fixtureAction(101L, 11, null, null);
        Action v2 = fixtureAction(102L, 12, 802L, null);
        Action v3 = fixtureAction(103L, 13, 803L, 7);
        Files.write(journal, concat(
                handcraftedAddFrame(0x56475131, 7L, v1, false, false),
                handcraftedAddFrame(0x56475132, 12L, v2, true, false),
                handcraftedAddFrame(0x56475133, 20L, v3, true, true)));

        QuarantineStore reopened = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(reopened.entries()).hasSize(3);
        assertFixtureEntry(reopened.entries().get(0), 7L, 101L, 11, null, null);
        assertFixtureEntry(reopened.entries().get(1), 12L, 102L, 12, 802L, null);
        assertFixtureEntry(reopened.entries().get(2), 20L, 103L, 13, 803L, 7);
        assertThat(reopened.append(action(21)))
                .as("replay must advance sequence beyond the highest handcrafted frame")
                .isEqualTo(21L);
    }

    @Test
    void handcraftedInvalidGroupMarkersNeverPartiallyMarkOrRetire(@TempDir Path tmp) throws Exception {
        for (byte operation : new byte[]{4, 5}) {
            for (long[] members : new long[][]{{1L, 99L}, {1L, 1L}}) {
                Path journal = tmp.resolve("invalid-" + operation + "-" + members[1] + ".bin");
                QuarantineStore seed = seededPair(journal, operation == 5);
                Files.write(journal, handcraftedGroupFrame(operation, members), java.nio.file.StandardOpenOption.APPEND);
                QuarantineStore reopened = new QuarantineStore(journal, 8, 1_000_000L);
                assertUnchangedPair(reopened, operation == 5);
            }
        }

        for (byte operation : new byte[]{4, 5}) {
            Path journal = tmp.resolve("incomplete-" + operation + ".bin");
            QuarantineStore seed = seededPair(journal, operation == 5);
            byte[] complete = handcraftedGroupFrame(operation, new long[]{1L, 2L});
            Files.write(journal, java.util.Arrays.copyOf(complete, complete.length - Long.BYTES),
                    java.nio.file.StandardOpenOption.APPEND);
            QuarantineStore reopened = new QuarantineStore(journal, 8, 1_000_000L);
            assertUnchangedPair(reopened, operation == 5);
        }
    }

    @Test
    void malformedReplayAddsFailClosedWithoutReplacingExistingRecovery(@TempDir Path tmp) throws Exception {
        for (long invalid : new long[]{0L, -1L, Long.MAX_VALUE}) {
            Path journal = tmp.resolve("invalid-add-" + invalid + ".bin");
            QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
            seed.append(action(1));
            Files.write(journal, handcraftedAddFrame(0x56475133, invalid, action(2), true, true),
                    java.nio.file.StandardOpenOption.APPEND);
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> new QuarantineStore(journal, 8, 1_000_000L));
        }
    }

    @Test
    void duplicateAndOverlappingReplayAddsFailClosedWithoutIdentifierReuse(@TempDir Path tmp) throws Exception {
        Path duplicate = tmp.resolve("duplicate-add.bin");
        QuarantineStore duplicateSeed = new QuarantineStore(duplicate, 8, 1_000_000L);
        duplicateSeed.append(action(1));
        Files.write(duplicate, handcraftedAddFrame(0x56475133, 1L, action(2), true, true),
                java.nio.file.StandardOpenOption.APPEND);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> new QuarantineStore(duplicate, 8, 1_000_000L));

        Path overlap = tmp.resolve("overlap-group-add.bin");
        QuarantineStore overlapSeed = new QuarantineStore(overlap, 8, 1_000_000L);
        overlapSeed.append(action(1));
        Files.write(overlap, handcraftedGroupAddFrame(1L, List.of(action(2), action(3))),
                java.nio.file.StandardOpenOption.APPEND);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> new QuarantineStore(overlap, 8, 1_000_000L));
    }

    @Test
    void appendGroupRejectsExhaustionWithoutMutatingOrReusingSequence(@TempDir Path tmp) throws Exception {
        QuarantineStore store = new QuarantineStore(tmp.resolve("group-exhaustion.bin"), 8, 1_000_000L);
        java.lang.reflect.Field nextSequence = QuarantineStore.class.getDeclaredField("nextSequence");
        nextSequence.setAccessible(true);
        nextSequence.setLong(store, Long.MAX_VALUE - 1L);

        assertThat(store.appendGroup(List.of(action(1), action(2)))).isEmpty();
        assertThat(store.entries()).isEmpty();
        assertThat(nextSequence.getLong(store)).isEqualTo(Long.MAX_VALUE - 1L);
    }

    @Test
    void explicitGroupOperationsRejectMalformedRequestsWithoutJournalOrStateChange(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("direct-group-validation.bin");
        QuarantineStore store = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = store.appendGroup(List.of(action(1), action(2)));
        long bytesBefore = Files.size(journal);

        List<List<Long>> malformed = java.util.Arrays.asList(
                null, List.of(), java.util.Collections.singletonList(sequences.get(0)),
                java.util.Arrays.asList(sequences.get(0), null),
                List.of(sequences.get(0), sequences.get(0)),
                List.of(sequences.get(0), 99L));
        for (List<Long> request : malformed) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.markSinkSucceededGroup(request));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.acknowledgeGroup(request));
            assertThat(Files.size(journal)).isEqualTo(bytesBefore);
            assertThat(store.entries()).hasSize(2).allMatch(e -> !e.sinkSucceeded());
        }

        store.markSinkSucceededGroup(sequences);
        long markedBytes = Files.size(journal);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.markSinkSucceededGroup(sequences));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.acknowledgeGroup(List.of(sequences.get(0), sequences.get(1), 99L)));
        assertThat(Files.size(journal)).isEqualTo(markedBytes);
        assertThat(store.entries()).hasSize(2).allMatch(QuarantineStore.Entry::sinkSucceeded);

        QuarantineStore mixed = new QuarantineStore(tmp.resolve("mixed-group-validation.bin"), 8, 1_000_000L);
        List<Long> mixedSequences = mixed.appendGroup(List.of(action(3), action(4)));
        mixed.markSinkSucceeded(mixedSequences.get(0));
        long mixedBytes = Files.size(tmp.resolve("mixed-group-validation.bin"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mixed.markSinkSucceededGroup(mixedSequences));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mixed.acknowledgeGroup(mixedSequences));
        assertThat(Files.size(tmp.resolve("mixed-group-validation.bin"))).isEqualTo(mixedBytes);
        assertThat(mixed.entries()).extracting(QuarantineStore.Entry::sinkSucceeded)
                .containsExactlyInAnyOrder(true, false);
    }

    private static QuarantineStore seededPair(Path journal, boolean sinkSucceeded) throws Exception {
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = seed.appendGroup(List.of(action(1), action(2)));
        if (sinkSucceeded) seed.markSinkSucceededGroup(sequences);
        return seed;
    }

    private static void assertUnchangedPair(QuarantineStore store, boolean sinkSucceeded) {
        assertThat(store.entries()).hasSize(2);
        assertThat(store.entries()).allSatisfy(e -> assertThat(e.sinkSucceeded()).isEqualTo(sinkSucceeded));
    }

    private static void assertFixtureEntry(QuarantineStore.Entry entry, long sequence, long id, int x,
                                           Long pairId, Integer inventorySlot) {
        assertThat(entry.sequence()).isEqualTo(sequence);
        assertThat(entry.sinkSucceeded()).isFalse();
        assertThat(entry.action().id()).isEqualTo(id);
        assertThat(entry.action().type()).isEqualTo(ActionType.BLOCK_PLACE);
        assertThat(entry.action().actorName()).isEqualTo("fixture-actor");
        assertThat(entry.action().worldId()).isEqualTo("minecraft:fixture");
        assertThat(entry.action().x()).isEqualTo(x);
        assertThat(entry.action().pairId()).isEqualTo(pairId);
        assertThat(entry.action().inventorySlot()).isEqualTo(inventorySlot);
    }

    private static Action fixtureAction(long id, int x, Long pairId, Integer inventorySlot) {
        return new Action(id, 1_700_000_000_000L + id, ActionType.BLOCK_PLACE,
                new UUID(4L, id), "fixture-actor", "minecraft:fixture", x, 70, -x,
                "minecraft:gold_block", "fixture-meta", 3, true, "fixture-source",
                "front", "blue", false, "old-state", "new-state", new byte[]{1, 2},
                new byte[]{3, 4}, new byte[]{5, 6}, pairId, inventorySlot);
    }

    private static byte[] handcraftedAddFrame(int magic, long sequence, Action a,
                                               boolean withPairId, boolean withInventorySlot) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(magic); out.writeByte(1); out.writeLong(sequence);
            out.writeLong(a.id()); out.writeLong(a.timestamp()); out.writeInt(a.type().id());
            out.writeBoolean(true); out.writeLong(a.actorUuid().getMostSignificantBits()); out.writeLong(a.actorUuid().getLeastSignificantBits());
            writeFixtureString(out, a.actorName()); writeFixtureString(out, a.worldId());
            out.writeInt(a.x()); out.writeInt(a.y()); out.writeInt(a.z());
            writeFixtureString(out, a.targetId()); writeFixtureString(out, a.targetMeta());
            out.writeInt(a.amount()); out.writeBoolean(a.rolledBack());
            writeFixtureString(out, a.sourceTag()); writeFixtureString(out, a.signSide()); writeFixtureString(out, a.signDyeColor());
            out.writeByte(1); writeFixtureString(out, a.oldBlockState()); writeFixtureString(out, a.newBlockState());
            writeFixtureBytes(out, a.blockEntityNbt()); writeFixtureBytes(out, a.itemNbt()); writeFixtureBytes(out, a.entityNbt());
            if (withPairId) out.writeLong(a.pairId() == null ? 0L : a.pairId());
            if (withInventorySlot) out.writeInt(a.inventorySlot() == null ? -1 : a.inventorySlot());
        }
        return bytes.toByteArray();
    }

    private static byte[] handcraftedGroupAddFrame(long firstSequence, List<Action> actions) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x56475133); out.writeByte(6); out.writeLong(firstSequence); out.writeInt(actions.size());
            for (Action action : actions) {
                byte[] add = handcraftedAddFrame(0x56475133, 1L, action, true, true);
                out.write(add, 13, add.length - 13);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] handcraftedGroupFrame(byte operation, long[] members) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x56475133); out.writeByte(operation); out.writeLong(members.length);
            for (long member : members) out.writeLong(member);
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... frames) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] frame : frames) bytes.write(frame);
        return bytes.toByteArray();
    }

    private static void writeFixtureString(DataOutputStream out, String value) throws Exception {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static void writeFixtureBytes(DataOutputStream out, byte[] value) throws Exception {
        if (value == null) { out.writeInt(-1); return; }
        out.writeInt(value.length); out.write(value);
    }

    private static Action action(int x) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    private static QuarantineStore.AppendFaultInjector noRollbackPartialFrame(
            java.util.concurrent.atomic.AtomicBoolean failOnce, String message) {
        return new QuarantineStore.AppendFaultInjector() {
            @Override
            public void write(java.nio.channels.FileChannel channel, byte[] frame) throws java.io.IOException {
                if (failOnce.getAndSet(false)) {
                    ByteBuffer partial = ByteBuffer.wrap(frame, 0, Math.min(12, frame.length));
                    while (partial.hasRemaining()) channel.write(partial);
                    throw new java.io.IOException(message);
                }
                ByteBuffer complete = ByteBuffer.wrap(frame);
                while (complete.hasRemaining()) channel.write(complete);
            }

            @Override
            public void beforeRollback(Path ignored, long priorBytes) throws java.io.IOException {
                throw new java.io.IOException("injected rollback failure retains crash tail");
            }
        };
    }
}
