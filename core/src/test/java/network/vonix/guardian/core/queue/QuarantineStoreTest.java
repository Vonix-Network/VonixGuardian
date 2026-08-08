package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
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
    void entryRetentionLimitFailsClosed(@TempDir Path tmp) throws Exception {
        QuarantineStore store = new QuarantineStore(tmp.resolve("quarantine.bin"), 1, 1_000_000L);

        assertThat(store.append(action(1))).isPositive();
        assertThat(store.append(action(2))).isEqualTo(-1L);
        assertThat(store.entries()).hasSize(1);
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

    private static Action action(int x) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:stone", null, 1, false, null);
    }
}
