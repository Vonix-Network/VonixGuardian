package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionJournalTest {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "vg-admission-test");
        t.setDaemon(true);
        return t;
    };

    private static Action action(int x) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    @Test
    void rejectedSubmitIsJournaledBeforeItIsCountedAsDropped() throws Exception {
        Path dir = Files.createTempDirectory("vg-admission-journal");
        QuarantineStore store = new QuarantineStore(dir.resolve("q.bin"), 1, 256L * 1024L);
        CapturingSink sink = new CapturingSink();
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(1, 5_000L, 4, sink, DAEMON, store)) {
            q.setPaused(true);
            assertThat(q.submit(action(1))).isTrue();

            assertThat(q.submit(action(2))).isFalse();
            assertThat(q.dropped()).isEqualTo(1L);
            assertThat(q.quarantineOverflow()).isZero();
            assertThat(q.quarantined()).isEqualTo(1L);
            assertThat(store.entries()).hasSize(1);
            assertThat(store.entries().get(0).action().x()).isEqualTo(2);

            assertThat(q.submit(action(3))).isFalse();
            assertThat(q.dropped()).isEqualTo(2L);
            assertThat(q.quarantineOverflow()).isGreaterThanOrEqualTo(1L);
            assertThat(q.quarantineRetentionLimitReached()).isTrue();
            assertThat(store.entries()).hasSize(1);
        }
    }

    @Test
    void rejectedSubmitWithoutAStoreDoesNotCountQuarantineOverflow() {
        CapturingSink sink = new CapturingSink();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(1, 5_000L, 4, sink, DAEMON);
        try {
            q.setPaused(true);
            assertThat(q.submit(action(1))).isTrue();
            assertThat(q.submit(action(2))).isFalse();
            assertThat(q.dropped()).isEqualTo(1L);
            assertThat(q.quarantineOverflow()).isZero();
            assertThat(q.quarantined()).isZero();
        } finally {
            q.close();
        }
    }

    @Test
    void journalRejectedAdmissionWritesBeforeTheDropCounter() throws Exception {
        Path dir = Files.createTempDirectory("vg-reject-admission");
        QuarantineStore store = new QuarantineStore(dir.resolve("q.bin"), 4, 256L * 1024L);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(4, 5_000L, 4, new CapturingSink(), DAEMON, store)) {
            q.journalRejectedAdmission(action(9));
            assertThat(q.dropped()).isEqualTo(1L);
            assertThat(store.entries()).extracting(e -> e.action().x()).containsExactly(9);
            assertThat(q.quarantineOverflow()).isZero();
        }
    }

    @Test
    void submitSourceJournalsBeforeCountingTheDrop() throws Exception {
        Path queue = repoRoot().resolve(
                "core/src/main/java/network/vonix/guardian/core/queue/BatchedAsyncWriteQueue.java");
        String src = Files.readString(queue);
        String submit = src.substring(src.indexOf("public boolean submit(Action a)"),
                src.indexOf("public boolean submitPair"));
        assertThat(submit.indexOf("journalBeforeDrop")).isLessThan(submit.indexOf("recordDrop"));
        String rejected = src.substring(src.indexOf("public void journalRejectedAdmission"),
                src.indexOf("private void journalBeforeDrop"));
        assertThat(rejected.indexOf("journalBeforeDrop")).isLessThan(rejected.indexOf("recordDrop"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))) return p;
        }
        return dir;
    }

    private static final class CapturingSink implements BatchSink {
        @Override public void flush(List<Action> batch) { }
    }
}
