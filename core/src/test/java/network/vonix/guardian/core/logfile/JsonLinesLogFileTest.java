package network.vonix.guardian.core.logfile;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLinesLogFileTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** A Clock whose instant is mutable for tests. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;
        MutableClock(Instant start) { this(new AtomicReference<>(start), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> ref, ZoneId zone) { this.now = ref; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
        void advance(Duration d) { now.set(now.get().plus(d)); }
    }

    private static Action sample(long ts, int i) {
        return new Action(
                -1L, ts, ActionType.BLOCK_BREAK,
                NOTCH, "Notch",
                "minecraft:overworld",
                100 + i, 64, -200,
                "minecraft:diamond_ore", null,
                1, false, null
        );
    }

    private static long countLines(Path p) throws Exception {
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.count();
        }
    }

    private static List<String> readGzipLines(Path gz) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            return r.lines().toList();
        }
    }

    private static List<Path> auditFiles(Path dir, String suffix) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void rotatesDailyAndPrunesPastRetention(@TempDir Path dir) throws Exception {
        // Day 1: 2025-01-10T12:00:00Z
        MutableClock clock = new MutableClock(Instant.parse("2025-01-10T12:00:00Z"));
        JsonLinesLogFile log = new JsonLinesLogFile(dir, true, 1, clock);

        // Write 100 events on day 1.
        long ts1 = clock.instant().toEpochMilli();
        for (int i = 0; i < 100; i++) {
            log.append(sample(ts1, i));
        }
        log.flush();

        Path day1 = dir.resolve("audit-2025-01-10.log.jsonl");
        assertThat(day1).exists();
        assertThat(countLines(day1)).isEqualTo(100L);
        // Capture day1 plaintext contents for the gzip round-trip check.
        List<String> day1Lines = Files.readAllLines(day1, StandardCharsets.UTF_8);

        // Advance clock by 1 day → 2025-01-11.
        clock.advance(Duration.ofDays(1));
        long ts2 = clock.instant().toEpochMilli();
        for (int i = 0; i < 100; i++) {
            log.append(sample(ts2, i));
        }
        log.flush();

        // Day 1 should have been gzipped, day 2 jsonl exists.
        Path day2 = dir.resolve("audit-2025-01-11.log.jsonl");
        Path day1Gz = dir.resolve("audit-2025-01-10.log.jsonl.gz");
        assertThat(day2).exists();
        assertThat(countLines(day2)).isEqualTo(100L);
        assertThat(day1Gz).exists();
        assertThat(day1).doesNotExist();

        // Two .jsonl-family files exist (day2 plain + day1 gz).
        List<Path> allRotated = auditFiles(dir, ".jsonl");
        List<Path> allGz = auditFiles(dir, ".jsonl.gz");
        assertThat(allRotated).hasSize(1);
        assertThat(allGz).hasSize(1);

        // Gzip round-trips to the original day-1 content.
        List<String> roundTripped = readGzipLines(day1Gz);
        assertThat(roundTripped).isEqualTo(day1Lines);

        // The JSON shape from § 6 — at least sanity-check the first day-1 line.
        String first = day1Lines.get(0);
        assertThat(first).startsWith("{\"ts\":");
        assertThat(first).contains("\"type\":\"BLOCK_BREAK\"");
        assertThat(first).contains("\"actor\":{\"uuid\":\"" + NOTCH + "\",\"name\":\"Notch\"}");
        assertThat(first).contains("\"world\":\"minecraft:overworld\"");
        assertThat(first).contains("\"pos\":[100,64,-200]");
        assertThat(first).contains("\"target\":\"minecraft:diamond_ore\"");
        assertThat(first).contains("\"amount\":1");
        assertThat(first).contains("\"source\":null");
        assertThat(first).contains("\"meta\":null");

        // Advance past retention (retentionDays=1) → day 3 → cutoff = day 2,
        // so day-1 gz must be pruned on next append.
        clock.advance(Duration.ofDays(1));
        long ts3 = clock.instant().toEpochMilli();
        log.append(sample(ts3, 0));
        log.flush();

        Path day3 = dir.resolve("audit-2025-01-12.log.jsonl");
        assertThat(day3).exists();
        assertThat(day1Gz).doesNotExist();
        // day 2 (boundary) is retained but rotated: it gets gzipped, not pruned.
        Path day2Gz = dir.resolve("audit-2025-01-11.log.jsonl.gz");
        assertThat(day2).doesNotExist();
        assertThat(day2Gz).exists();

        log.close();
    }

    @Test
    void appendDoesNotThrowOnNullAction(@TempDir Path dir) {
        JsonLinesLogFile log = new JsonLinesLogFile(
                dir, false, 0, Clock.fixed(Instant.parse("2025-01-10T00:00:00Z"), ZoneOffset.UTC));
        log.append(null);
        log.close();
    }
}
