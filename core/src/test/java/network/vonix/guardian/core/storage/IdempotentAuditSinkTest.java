package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.logfile.JsonLinesLogFile;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentAuditSinkTest {

    @TempDir Path tmp;

    @Test
    void nonIdenticalPendingBatchIsNotAcknowledgedAsRetry() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("distinct-batch.db"));
        dao.init();
        IdempotentAuditSink sink = new IdempotentAuditSink(dao, new AtomicReference<>(null));
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000071");
        Action pending = new Action(-1L, 1_700_000_000_001L, ActionType.BLOCK_PLACE,
                actor, "tester", "minecraft:overworld",
                3, 64, 9, "minecraft:stone", null, 1, false, null);
        Action current = new Action(-1L, 1_700_000_000_001L, ActionType.BLOCK_PLACE,
                actor, "tester", "minecraft:overworld",
                3, 64, 9, "minecraft:stone", "different-meta", 1, false, "manual");

        sink.failNextJsonlAppends(1);
        assertThatThrownBy(() -> sink.flush(List.of(pending)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("controlled JSONL failure");

        sink.flush(List.of(current));

        assertThat(dao.query(network.vonix.guardian.core.query.QueryFilter.empty(), 0, 10))
                .as("a non-identical current batch must not be lost as a retry")
                .hasSize(2);
        assertThat(dao.peekSinkOutbox()).isNull();
        dao.close();
    }


    @Test
    void jsonlFailureAfterJdbcDoesNotDuplicateRowsOnRetry() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("audit.db"));
        dao.init();
        Path logDir = tmp.resolve("logs");
        JsonLinesLogFile log = new JsonLinesLogFile(logDir, false, 1, Clock.systemUTC());
        IdempotentAuditSink sink = new IdempotentAuditSink(dao, new AtomicReference<>(log));
        Action row = new Action(-1L, 1_700_000_000_000L, ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                3, 64, 9, "minecraft:stone", null, 1, false, null);

        sink.failNextJsonlAppends(1);
        assertThatThrownBy(() -> sink.flush(List.of(row)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("controlled JSONL failure");

        assertThat(dao.query(network.vonix.guardian.core.query.QueryFilter.empty(), 0, 10)).hasSize(1);
        assertThat(dao.peekSinkOutbox()).isNotNull();

        sink.flush(List.of(row));

        assertThat(dao.query(network.vonix.guardian.core.query.QueryFilter.empty(), 0, 10)).hasSize(1);
        assertThat(dao.peekSinkOutbox()).isNull();
        long lines = Files.list(logDir)
                .filter(p -> p.getFileName().toString().endsWith(".log.jsonl")
                        || p.getFileName().toString().endsWith(".log"))
                .mapToLong(p -> {
                    try {
                        return Files.lines(p).count();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .sum();
        assertThat(lines).isEqualTo(1L);
        dao.close();
        log.close();
    }
}
