/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.perf;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.queue.BatchSink;
import network.vonix.guardian.core.queue.BatchedAsyncWriteQueue;
import network.vonix.guardian.core.queue.EntityBlockChangeCoalescer;
import network.vonix.guardian.core.rollback.RollbackPlan;
import network.vonix.guardian.core.rollback.RollbackResult;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

/**
 * Offline, dependency-free micro-benchmark for the four engine hot paths that
 * need no Minecraft classes:
 *
 * <ol>
 *   <li><strong>query parse</strong> — {@link QueryParser#parse} over a fixed
 *       corpus of valid {@code /vg lookup} filters, plus a separate malformed
 *       corpus for the rejection path</li>
 *   <li><strong>plan build</strong> — {@link RollbackPlan#build} over synthetic
 *       DAO result sets in both {@code ROLLBACK} and {@code RESTORE} mode</li>
 *   <li><strong>queue admit</strong> — {@link BatchedAsyncWriteQueue#submit}
 *       with the worker parked (accepted) and with a saturated ring (rejected)</li>
 *   <li><strong>coalescer merge</strong> —
 *       {@link EntityBlockChangeCoalescer#shouldLog} on three workloads that
 *       provably land on three different branches, verified against the
 *       instance's own counters</li>
 * </ol>
 *
 * <h2>This is a measurement tool, not a gate</h2>
 *
 * <p>It asserts nothing and fails no build. It prints numbers plus the
 * provenance needed to challenge them: the SHA-256 and mtime of the exact class
 * file each measured type was loaded from, and a reflective check for methods
 * that only exist at HEAD. If the numbers came from stale bytecode, the report
 * says so in its own output rather than leaving the reader to guess.</p>
 *
 * <p>See {@code docs-internal/PERF-METHOD.md} for the method, the workload
 * definitions, and the explicit list of what is <em>not</em> measured.</p>
 *
 * <h2>Why not JMH</h2>
 *
 * <p>JMH cannot be added: {@code core/} resolves from {@code mavenCentral()} and
 * the offline build must not gain an artifact. The cost is real and is stated
 * rather than hidden — no forked JVMs, no dead-code elimination control beyond a
 * post-loop sink, and therefore no separation of JIT profile between warmup and
 * measurement. Percentiles are <em>observed</em>, not JMH-grade.</p>
 *
 * <h2>Running it</h2>
 *
 * <p>As a source file, which is how the published report was produced — no
 * project build and no separate compile step:</p>
 *
 * <pre>
 *   java -Xmx1g -cp core/build/classes/java/main:core/build/resources/main \
 *        core/src/test/java/network/vonix/guardian/core/perf/PerfReport.java
 * </pre>
 *
 * <p>Or from the test classpath after a normal {@code :core} build:</p>
 *
 * <pre>
 *   java -cp core/build/classes/java/main:core/build/resources/main:&lt;deps&gt; \
 *        network.vonix.guardian.core.perf.PerfReport
 * </pre>
 *
 * <p>{@code core/} declares {@code slf4j-api} and {@code gson} as
 * {@code compileOnly}, so put them on the classpath. SLF4J will warn that it
 * found no provider; that is harmless, because every measured path only logs on
 * failure.</p>
 */
public final class PerfReport {

    // ---------------------------------------------------------------- tunables

    /** Per-workload warmup floor: at least this much wall clock and at least minIters calls. */
    private static final long WARMUP_BUDGET_NS = 400_000_000L;
    /** Per-workload measurement floor: never stop before this much wall clock has elapsed. */
    private static final long MEASURE_BUDGET_NS = 400_000_000L;
    /** Hard cap on retained samples, so a very fast op cannot grow the array without bound. */
    private static final int MAX_SAMPLES = 2_000_000;

    private static final int PARSE_MIN_ITERS = 500_000;
    private static final int PARSE_REJECT_MIN_ITERS = 20_000;

    /** Plan-build sizes, chosen around the shipped PAGE_SIZE of 5000. */
    private static final int[] PLAN_SIZES = {1_000, 5_000, 20_000};
    private static final int[] PLAN_MIN_ITERS = {2_000, 400, 100};

    private static final int ADMIT_MIN_ITERS = 500_000;
    /**
     * Ring capacity for the accepted-admit workload. Must exceed
     * {@link #MAX_SAMPLES}: the worker is parked, so every accepted slot stays
     * occupied, and a full ring would silently turn the samples into the
     * rejection path. Warmup runs on its own queue so it does not consume slots.
     */
    private static final int ADMIT_CAPACITY = 1 << 22;
    /** Deliberately tiny ring so every submit after the first few is rejected. */
    private static final int ADMIT_FULL_CAPACITY = 8;

    private static final int COALESCE_MIN_ITERS = 400_000;
    /** Fresh coalescer instances for the pure-insert workload. */
    private static final int COALESCE_FILL_REPEATS = 64;
    private static final long COALESCE_WINDOW_MS = EntityBlockChangeCoalescer.DEFAULT_WINDOW_MS;
    private static final int COALESCE_MAX_ENTRIES = EntityBlockChangeCoalescer.DEFAULT_MAX_ENTRIES;

    /** Deterministic seed so the workload is byte-identical across runs. */
    private static final long SEED = 0x5EED_1032L;

    private static final String WORLD = "minecraft:overworld";
    private static final long NOW_MS = 1_700_000_000_000L;

    /** Written only after a timed region, never inside one. */
    private static volatile long sink;

    private PerfReport() {}

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        printPreamble();
        printProvenance();
        printFloor();

        runParse();
        runParseReject();
        runPlanBuild();
        runAdmit();
        runAdmitFull();
        runCoalescer();

        System.out.println();
        System.out.println("# end-of-run sink=" + sink);
        System.out.println("# no assertion was made; these are observations.");
    }

    // ---------------------------------------------------------------- 1. parse

    /**
     * A fixed, hand-written corpus covering every token family the parser
     * accepts: user, time (single, range, fractional, CoreProtect comma form),
     * radius (numeric, {@code #global}, {@code #world_*}, dimension shorthand),
     * position, action (signed token, bare family, multi-target alias,
     * CoreProtect scalar alias), include/exclude identifier lists, and all five
     * hash flags.
     *
     * <p>All entries are valid. That defines what the number means: the success
     * path never touches {@link network.vonix.guardian.core.i18n.Messages}, so
     * no resource-bundle I/O is included. Rejection is a different cost centre,
     * measured separately in {@link #runParseReject()}.</p>
     */
    private static String[] parseCorpus() {
        return new String[] {
            "u:Notch t:1h r:20",
            "u:Notch t:1h r:20 a:-block #count",
            "u:Notch,Herobrine t:2w,5d,7h,2m,10s r:#global a:+block",
            "r:10 t:3d a:block i:minecraft:stone,minecraft:dirt,minecraft:oak_log "
                + "e:minecraft:bedrock,minecraft:water",
            "r:10 t:1h-2h a:inventory #verbose",
            "p:100,64,-200 r:5 a:kill e:minecraft:zombie",
            "t:0.5h a:container #preview",
            "r:#nether t:6h a:session",
            "r:#world_overworld a:login #silent",
            "r:32 t:1d a:entity #optimize",
        };
    }

    private static void runParse() {
        QueryParser parser = new QueryParser();
        // A null context is console origin and rejects r:<n>; supply a position,
        // exactly as a player-issued lookup does.
        QueryParser.QueryParseContext ctx = new QueryParser.QueryParseContext(0, 64, 0, null);
        String[] corpus = parseCorpus();
        int[] idx = {0};

        measure("parse/corpus", PARSE_MIN_ITERS,
            "valid filters, " + corpus.length + " distinct strings, player ctx", "",
            () -> {
                String s = corpus[idx[0]];
                idx[0] = (idx[0] + 1) % corpus.length;
                QueryFilter f = parser.parse(s, ctx);
                return f.actions().size() + f.include().size();
            });
    }

    /**
     * The rejection path is a genuinely different cost centre: it formats a
     * message through {@link java.text.MessageFormat}, and an unknown
     * {@code a:} token additionally builds a suggestion string by joining every
     * {@link ActionType} token and every category name.
     *
     * <p>Operators do typo filters, so this is real — but it is bounded by a
     * human typing, so it is reported separately and must not be folded into the
     * success-path figure. Exception construction is inside the timed region
     * because a caller cannot avoid paying it.</p>
     */
    private static void runParseReject() {
        QueryParser parser = new QueryParser();
        QueryParser.QueryParseContext ctx = new QueryParser.QueryParseContext(0, 64, 0, null);
        String[] bad = {
            "a:notarealaaction",  // builds the full suggestion string
            "z:whatever",          // unknown prefix
            "nocolonhere",         // missing separator
            "u:bad name!",         // invalid player name
            "t:5q",                // unknown duration unit
        };
        int[] idx = {0};

        measure("parse/reject", PARSE_REJECT_MIN_ITERS,
            "malformed filters (" + bad.length + " shapes), exception included", "",
            () -> {
                String s = bad[idx[0]];
                idx[0] = (idx[0] + 1) % bad.length;
                try {
                    parser.parse(s, ctx);
                    return 0L;
                } catch (RuntimeException expected) {
                    return 1L;
                }
            });
    }

    // ---------------------------------------------------------------- 2. plan

    /**
     * Builds a synthetic DAO result set with the mix a real {@code /vg rollback}
     * sees, then times plan construction.
     *
     * <p>Mix by construction, per 10 rows:</p>
     * <ul>
     *   <li>7 {@code BLOCK_PLACE}/{@code BLOCK_BREAK} on distinct positions —
     *       the de-dupe-eligible majority</li>
     *   <li>1 duplicate position — exercises the {@code SUPERSEDED_POSITION}
     *       branch and its skip bookkeeping</li>
     *   <li>1 {@code CHAT} — exercises the {@code AUDIT_ONLY} reject branch</li>
     *   <li>1 {@code ENTITY_KILL} with a target id — planned in {@code ROLLBACK},
     *       rejected as {@code UNSUPPORTED_ENTITY} in {@code RESTORE}, so the two
     *       modes are genuinely different workloads rather than the same one
     *       relabeled</li>
     * </ul>
     *
     * <p>Ids and timestamps descend together, matching the DAO's canonical
     * {@code ts DESC, id DESC} page order, so the comparison sort is not handed
     * random keys.</p>
     */
    private static List<Action> planRows(int n) {
        Random rnd = new Random(SEED);
        UUID actor = UUID.nameUUIDFromBytes("perf-actor".getBytes());
        List<Action> rows = new ArrayList<>(n);
        int pos = 0;
        for (int i = 0; i < n; i++) {
            long id = n - i;
            long ts = NOW_MS - i;
            int bucket = i % 10;
            if (bucket < 7) {
                ActionType t = (bucket & 1) == 0 ? ActionType.BLOCK_PLACE : ActionType.BLOCK_BREAK;
                pos++;
                rows.add(new Action(id, ts, t, actor, "perf", WORLD,
                    pos & 0x3FF, 64, pos >> 10, "minecraft:stone", null, 1, false, null));
            } else if (bucket == 7) {
                rows.add(new Action(id, ts, ActionType.BLOCK_PLACE, actor, "perf", WORLD,
                    pos & 0x3FF, 64, pos >> 10, "minecraft:dirt", null, 1, false, null));
            } else if (bucket == 8) {
                rows.add(new Action(id, ts, ActionType.CHAT, actor, "perf", WORLD,
                    0, 0, 0, "hello there", null, 0, false, null));
            } else {
                rows.add(new Action(id, ts, ActionType.ENTITY_KILL, actor, "perf", WORLD,
                    rnd.nextInt(1000), 64, rnd.nextInt(1000),
                    "minecraft:zombie", null, 1, false, null));
            }
        }
        return rows;
    }

    private static void runPlanBuild() {
        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(NOW_MS - 86_400_000L)
            .radius(10)
            .center(0, 64, 0)
            .build();
        UUID actor = UUID.nameUUIDFromBytes("perf-actor".getBytes());

        for (int s = 0; s < PLAN_SIZES.length; s++) {
            int n = PLAN_SIZES[s];
            int minIters = PLAN_MIN_ITERS[s];
            List<Action> rows = planRows(n);
            for (RollbackResult.Mode mode : RollbackResult.Mode.values()) {
                measure("plan/" + mode.name().toLowerCase(Locale.ROOT) + "/" + n, minIters,
                    n + " rows; 7 place/break, 1 superseded, 1 chat, 1 entity_kill",
                    "mode=" + mode.name(), () -> {
                        RollbackPlan p = RollbackPlan.build(rows, filter, mode, actor);
                        return p.plannedSteps() + p.skipped().size();
                    });
            }
        }
    }

    // ---------------------------------------------------------------- 3. admit

    /**
     * Times {@link BatchedAsyncWriteQueue#submit} with the worker parked via
     * {@link BatchedAsyncWriteQueue#setPaused(boolean)}.
     *
     * <p>Parking is what makes this an <em>admission</em> number rather than a
     * mixed admission-plus-drain number: nothing leaves the ring while the
     * measurement runs, so every sample pays the real producer-side cost — the
     * {@code recordSubmit} per-type counters and rate buckets, the
     * {@code admissionLock} critical section, and the {@code offer}.</p>
     *
     * <p>Warmup runs on a separate queue instance. If warmup shared the measured
     * ring it would fill it, and the later samples would measure rejection while
     * being labelled admission. The accepted/rejected split is printed with the
     * result so a reader can confirm nothing was contaminated.</p>
     *
     * <p>A single {@link Action} instance is re-submitted. That is deliberate:
     * the question is what admission costs once the producer already holds a row,
     * not what {@code new Action(...)} costs.</p>
     */
    private static void runAdmit() throws Exception {
        BatchSink discard = batch -> { /* discard: this measures admission only */ };
        Action a = blockPlaceAction();

        // Warmup, on a throwaway queue so the measured ring stays empty. The
        // measured call then passes warmIters=1 / no warm budget: measure() would
        // otherwise warm up on the measured instance and fill the very ring the
        // samples need.
        BatchedAsyncWriteQueue warm =
            new BatchedAsyncWriteQueue(ADMIT_CAPACITY, 50L, 1000, discard, daemonFactory());
        try {
            warm.setPaused(true);
            Thread.sleep(100L);
            long ws = System.nanoTime();
            long warmAcc = 0L;
            long n = 0L;
            while (n < ADMIT_MIN_ITERS || System.nanoTime() - ws < WARMUP_BUDGET_NS) {
                warmAcc += warm.submit(a) ? 1L : 0L;
                n++;
            }
            sink += warmAcc;
        } finally {
            warm.setPaused(false);
            warm.drainAndFlush(20_000L);
        }

        BatchedAsyncWriteQueue queue =
            new BatchedAsyncWriteQueue(ADMIT_CAPACITY, 50L, 1000, discard, daemonFactory());
        try {
            queue.setPaused(true);
            Thread.sleep(100L);
            long[] accepted = {0L};
            long[] rejected = {0L};
            Result r = measure("queue/admit", ADMIT_MIN_ITERS, 1, 0L,
                "capacity " + ADMIT_CAPACITY + ", worker parked, 1 producer, warmed on a separate queue",
                "accepted", null, () -> {
                    boolean ok = queue.submit(a);
                    if (ok) {
                        accepted[0]++;
                    } else {
                        rejected[0]++;
                    }
                    return ok ? 1L : 0L;
                });
            System.out.println("   context: depth=" + queue.depth()
                + " accepted=" + accepted[0]
                + " rejected=" + rejected[0]
                + " dropped=" + queue.dropped()
                + " paused=" + queue.isPaused());
            if (rejected[0] > 0) {
                System.out.println("   WARNING: " + rejected[0]
                    + " samples measured the rejection path, not admission."
                    + " Treat this row as contaminated. r.n=" + r.n);
            }
        } finally {
            queue.setPaused(false);
            queue.drainAndFlush(20_000L);
        }
    }

    /**
     * The full-ring rejection path. The ring is 8 slots and the worker stays
     * parked, so every submit after the eighth is rejected and pays
     * {@code journalBeforeDrop} (a no-op with no quarantine configured),
     * {@code recordDrop}, and the rate-limited drop-log gate.
     *
     * <p>Worth measuring because it answers a question operators ask during an
     * incident: does an overflowing queue become a CPU problem as well as a
     * data-loss problem?</p>
     */
    private static void runAdmitFull() throws Exception {
        BatchSink discard = batch -> { };
        BatchedAsyncWriteQueue queue =
            new BatchedAsyncWriteQueue(ADMIT_FULL_CAPACITY, 50L, 1000, discard, daemonFactory());
        try {
            Action a = blockPlaceAction();
            queue.setPaused(true);
            Thread.sleep(100L);

            long[] accepted = {0L};
            long[] rejected = {0L};
            Result r = measure("queue/admit-full", ADMIT_MIN_ITERS, ADMIT_MIN_ITERS, WARMUP_BUDGET_NS,
                "capacity " + ADMIT_FULL_CAPACITY + ", saturated, worker parked, 1 producer",
                "rejected", null, () -> {
                    boolean ok = queue.submit(a);
                    if (ok) {
                        accepted[0]++;
                    } else {
                        rejected[0]++;
                    }
                    return ok ? 1L : 0L;
                });
            System.out.println("   context: depth=" + queue.depth()
                + " accepted=" + accepted[0]
                + " rejected=" + rejected[0]
                + " dropped=" + queue.dropped()
                + " paused=" + queue.isPaused());
            if (accepted[0] > ADMIT_FULL_CAPACITY) {
                System.out.println("   WARNING: more accepts (" + accepted[0]
                    + ") than capacity, so the ring drained during measurement."
                    + " r.n=" + r.n);
            }
        } finally {
            queue.setPaused(false);
            queue.drainAndFlush(20_000L);
        }
    }

    private static Action blockPlaceAction() {
        return new Action(1L, NOW_MS, ActionType.BLOCK_PLACE,
            UUID.nameUUIDFromBytes("perf-actor".getBytes()), "perf", WORLD,
            0, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    private static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "perf-queue-worker");
            t.setDaemon(true);
            return t;
        };
    }

    // ---------------------------------------------------------------- 4. coalesce

    /**
     * Three coalescer workloads, each landing on a different branch. The
     * instance's own counters are printed with every row, so a workload that
     * drifted onto another branch is visible in the output rather than assumed
     * away.
     *
     * <ul>
     *   <li><strong>hit</strong> — one key repeated. First call is a miss; every
     *       measured call is an in-window hit. Cost: one
     *       {@link java.util.concurrent.ConcurrentHashMap} get, one
     *       {@code AtomicLong.get}, one hit counter.</li>
     *   <li><strong>fill-to-cap</strong> — pure insert cost. A fresh coalescer
     *       per repetition, inserting exactly {@code maxEntries} distinct keys so
     *       the map reaches the cap but never exceeds it and no sweep runs. A
     *       fresh instance per repetition is required: inserts are the only
     *       branch that consumes new keys, and any sustained insert stream must
     *       eventually hit the cap. Instance construction is included in the
     *       first sample of each repetition and is amortised over
     *       {@code maxEntries} inserts.</li>
     *   <li><strong>burst-over-cap</strong> — the modpack dragon-flood shape:
     *       distinct keys far past the cap on one instance. It fills, sweeps,
     *       evicts nothing (every entry is inside the window), then drops for the
     *       back-off interval. The result is a <em>mixture</em> of insert and drop
     *       branches, which is what a real burst does; the counters say how it
     *       split.</li>
     * </ul>
     *
     * <p>The production {@link System#nanoTime} clock is used, not an injected
     * fake, so the window arithmetic is the real one. That makes the hit/miss
     * split time-dependent rather than structurally fixed, which is exactly why
     * the counters are reported instead of assumed.</p>
     */
    private static void runCoalescer() {
        coalescerHit();
        coalescerFillToCap();
        coalescerBurst();
    }

    private static void coalescerHit() {
        EntityBlockChangeCoalescer c =
            new EntityBlockChangeCoalescer(COALESCE_WINDOW_MS, COALESCE_MAX_ENTRIES);
        long[] n = {0L};
        // reset() on the post-warmup hook: the counters must describe the measured
        // region only. It also clears the map, so the measured region starts with
        // the single-key miss the 'hit' label assumes, then stays on the hit branch.
        measure("coalesce/hit", COALESCE_MIN_ITERS, COALESCE_MIN_ITERS, WARMUP_BUDGET_NS,
            "1 key repeated; in-window hit branch", "counters are measured-region only",
            c::reset, () -> {
                n[0]++;
                return c.shouldLog("actor-0", WORLD, 0, 64, 0) ? 1L : 0L;
            });
        printCounts("coalesce/hit", c, n[0]);
    }

    private static void coalescerFillToCap() {
        int keys = COALESCE_MAX_ENTRIES;
        long[] samples = new long[COALESCE_FILL_REPEATS * keys];
        int count = 0;
        long acc = 0L;
        EntityBlockChangeCoalescer last = null;

        long t0 = System.nanoTime();
        for (int rep = 0; rep < COALESCE_FILL_REPEATS; rep++) {
            EntityBlockChangeCoalescer c =
                new EntityBlockChangeCoalescer(COALESCE_WINDOW_MS, COALESCE_MAX_ENTRIES);
            last = c;
            for (int k = 0; k < keys; k++) {
                long a = System.nanoTime();
                acc += c.shouldLog("actor-0", WORLD, k, 64, 0) ? 1L : 0L;
                long b = System.nanoTime();
                samples[count++] = b - a;
            }
        }
        long elapsed = System.nanoTime() - t0;
        sink += acc;

        emit("coalesce/fill-to-cap", count, elapsed, samples,
            keys + " distinct keys per repetition, " + COALESCE_FILL_REPEATS
                + " fresh instances, never past cap " + COALESCE_MAX_ENTRIES,
            "pure insert branch; instance construction amortised");
        if (last != null) {
            printCounts("coalesce/fill-to-cap", last, keys);
            System.out.println("   note: counters above are the LAST repetition only, not the sum.");
        }
    }

    private static void coalescerBurst() {
        EntityBlockChangeCoalescer c =
            new EntityBlockChangeCoalescer(COALESCE_WINDOW_MS, COALESCE_MAX_ENTRIES);
        long[] n = {0L};
        measure("coalesce/burst-over-cap", COALESCE_MIN_ITERS, COALESCE_MIN_ITERS, WARMUP_BUDGET_NS,
            "distinct keys past cap " + COALESCE_MAX_ENTRIES + "; insert then drop",
            "counters are measured-region only", c::reset, () -> {
                long k = n[0];
                n[0]++;
                // Cast keeps coordinates in int range for the whole run.
                return c.shouldLog("actor-0", WORLD, (int) (k & 0x0FFF_FFFF), 64, 0) ? 1L : 0L;
            });
        printCounts("coalesce/burst-over-cap", c, n[0]);
    }

    private static void printCounts(String name, EntityBlockChangeCoalescer c, long calls) {
        System.out.println("   context: calls=" + calls
            + " hits=" + c.hits()
            + " misses=" + c.misses()
            + " capDrops=" + c.capDrops()
            + " evictions=" + c.evictions()
            + " size=" + c.size());
        System.out.println("COUNTS name=" + name + " calls=" + calls
            + " hits=" + c.hits() + " misses=" + c.misses()
            + " capDrops=" + c.capDrops() + " evictions=" + c.evictions()
            + " size=" + c.size());
    }

    // ---------------------------------------------------------------- harness

    /** A measured operation returning a value the JIT cannot elide. */
    @FunctionalInterface
    private interface Op {
        long run();
    }

    /** Timing and count for one emitted row. */
    private static final class Result {
        final int n;

        Result(int n) {
            this.n = n;
        }
    }

    /**
     * Warm up by wall clock, then measure by wall clock.
     *
     * <p>Wall-clock budgets rather than fixed iteration counts, because the four
     * paths differ by three orders of magnitude: a fixed count either hands the
     * 20k-row plan build four samples or makes the coalescer run for minutes.
     * Warmup is a floor on both time and calls so a very fast op is still
     * JIT-compiled before measurement begins.</p>
     *
     * <p>The accumulator is a local, not the {@code volatile} sink, so the timed
     * region contains only the operation under test. The volatile write happens
     * once, after the loop.</p>
     */
    private static Result measure(String name, int minIters, int warmIters, long warmBudgetNs,
                                  String workload, String note, Runnable afterWarmup, Op op) {
        long warmStart = System.nanoTime();
        long warmed = 0L;
        long warmAcc = 0L;
        while (warmed < warmIters || System.nanoTime() - warmStart < warmBudgetNs) {
            warmAcc += op.run();
            warmed++;
        }
        sink += warmAcc;
        // Warmup shares the op lambda, so any counter the op touches must be
        // snapshotted/cleared here or it silently reports warmup + measurement.
        if (afterWarmup != null) {
            afterWarmup.run();
        }

        long[] samples = new long[Math.min(MAX_SAMPLES, Math.max(1024, minIters))];
        int count = 0;
        long acc = 0L;
        long t0 = System.nanoTime();
        long deadline = t0 + MEASURE_BUDGET_NS;
        while (true) {
            long a = System.nanoTime();
            acc += op.run();
            long b = System.nanoTime();
            if (count == samples.length) {
                if (samples.length >= MAX_SAMPLES) {
                    break;
                }
                samples = Arrays.copyOf(samples, Math.min(MAX_SAMPLES, samples.length * 2));
            }
            samples[count++] = b - a;
            if (count >= minIters && System.nanoTime() >= deadline) {
                break;
            }
        }
        long elapsed = System.nanoTime() - t0;
        sink += acc;

        // Only the filled prefix is statistics; the array's spare tail is not.
        emit(name, count, elapsed, Arrays.copyOf(samples, count), workload, note);
        return new Result(count);
    }

    /** Full-budget warmup, for the paths that have no per-instance capacity to exhaust. */
    private static Result measure(String name, int minIters, String workload, String note, Op op) {
        return measure(name, minIters, minIters, WARMUP_BUDGET_NS, workload, note, null, op);
    }

    /** Explicit warmup parameters plus a post-warmup hook. */
    private static Result measure(String name, int minIters, int warmIters, long warmBudgetNs,
                                  String workload, String note, Op op) {
        return measure(name, minIters, warmIters, warmBudgetNs, workload, note, null, op);
    }

    /**
     * Measures the harness's own floor: two {@code nanoTime} calls and a local
     * add with no engine work between them. Any published per-op figure at or
     * below this is harness noise rather than engine cost.
     */
    private static void printFloor() {
        int n = 500_000;
        long[] samples = new long[n];
        long acc = 0L;
        for (int i = 0; i < 200_000; i++) {
            long a = System.nanoTime();
            acc += 1L;
            long b = System.nanoTime();
            sink += b - a;
        }
        for (int i = 0; i < n; i++) {
            long a = System.nanoTime();
            acc += 1L;
            long b = System.nanoTime();
            samples[i] = b - a;
        }
        sink += acc;
        Stats st = Stats.of(samples);
        System.out.println();
        System.out.println("## harness floor");
        System.out.printf(Locale.ROOT,
            "   n=%-8d p50=%-9d p95=%-9d p99=%-9d  (ns; two nanoTime calls, no work)%n",
            n, st.p50, st.p95, st.p99);
        System.out.println("   Every row below is a measured value of which at least this much is harness.");
    }

    private static void printPreamble() {
        Runtime rt = Runtime.getRuntime();
        System.out.println("# VonixGuardian engine hot-path report (offline; no Minecraft, no project build)");
        System.out.println("# generated-by  = PerfReport.main");
        System.out.println("# epoch-ms      = " + System.currentTimeMillis());
        System.out.println("# java.version  = " + System.getProperty("java.version"));
        System.out.println("# java.vm.name  = " + System.getProperty("java.vm.name"));
        System.out.println("# os            = " + System.getProperty("os.name")
            + " / " + System.getProperty("os.arch"));
        System.out.println("# availableProc = " + rt.availableProcessors());
        System.out.println("# maxHeapBytes  = " + rt.maxMemory());
        System.out.println("# seed          = 0x" + Long.toHexString(SEED));
        System.out.println("# threads       = 1 measurement thread; 1 parked worker for the queue rows");
        System.out.println("# timing        = System.nanoTime around a single call; per-op samples retained");
        System.out.println("# percentiles   = nearest-rank over the retained sample array");
        System.out.println("# warmup        = at least " + WARMUP_BUDGET_NS + " ns and minIters calls per workload");
        System.out.println("# measure       = at least " + MEASURE_BUDGET_NS + " ns, capped at "
            + MAX_SAMPLES + " retained samples");
        System.out.println("# NOTE          = not JMH: no forked JVM, no JIT-profile isolation");
    }

    /**
     * Prints the SHA-256, mtime, and code source of the exact class file each
     * measured type was loaded from, plus a reflective check for methods that
     * exist only at HEAD.
     *
     * <p>This exists because the engine sources move faster than the build
     * output. A percentile is only meaningful against specific bytecode, so the
     * bytecode identity is printed with the number rather than assumed.</p>
     */
    private static void printProvenance() {
        System.out.println("# --- measured bytecode ---");
        Class<?>[] measured = {
            QueryParser.class,
            RollbackPlan.class,
            BatchedAsyncWriteQueue.class,
            EntityBlockChangeCoalescer.class,
        };
        for (Class<?> c : measured) {
            System.out.println("# class " + c.getName()
                + " sha256=" + sha256OfClass(c)
                + " mtime=" + mtimeOfClass(c)
                + " src=" + codeSourceOf(c));
        }
        // RollbackResult.SkipReason and RollbackPlan#skippedByReason arrived with the
        // plan-time rejection work. Their absence proves the loaded RollbackPlan
        // predates it, which changes what plan/* measures.
        System.out.println("# has RollbackResult.SkipReason = " + hasClass(
            "network.vonix.guardian.core.rollback.RollbackResult$SkipReason"));
        System.out.println("# has RollbackPlan.skippedByReason() = " + hasMethod(
            RollbackPlan.class, "skippedByReason"));
        System.out.println("# has RollbackPlan.skippedCount(...) = " + hasMethod(
            RollbackPlan.class, "skippedCount"));
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, PerfReport.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256OfClass(Class<?> c) {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            if (in == null) {
                return "unavailable";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String mtimeOfClass(Class<?> c) {
        URL url = c.getResource("/" + c.getName().replace('.', '/') + ".class");
        if (url == null || !"file".equals(url.getProtocol())) {
            return "unknown";
        }
        try {
            return String.valueOf(Files.getLastModifiedTime(Path.of(url.toURI())).toMillis());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String codeSourceOf(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            return cs == null || cs.getLocation() == null ? "unknown" : cs.getLocation().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * One result row plus a machine-readable {@code RESULT} line intended to be
     * quoted verbatim or diffed between runs.
     */
    private static void emit(String name, int n, long elapsedNs, long[] samples,
                             String workload, String note) {
        Stats st = Stats.of(samples);
        double thr = n / (elapsedNs / 1_000_000_000.0);

        System.out.println();
        System.out.println("## " + name);
        System.out.println("   workload: " + workload);
        if (!note.isEmpty()) {
            System.out.println("   note: " + note);
        }
        System.out.printf(Locale.ROOT,
            "   n=%-8d p50=%-10d p95=%-10d p99=%-10d mean=%-12.0f min=%-9d max=%-11d thr=%,.0f/s%n",
            n, st.p50, st.p95, st.p99, st.mean, st.min, st.max, thr);
        System.out.printf(Locale.ROOT,
            "RESULT name=%s n=%d p50_ns=%d p95_ns=%d p99_ns=%d mean_ns=%.0f min_ns=%d max_ns=%d throughput_per_sec=%.0f%n",
            name, n, st.p50, st.p95, st.p99, st.mean, st.min, st.max, thr);
    }

    /** Nearest-rank percentiles over the retained raw samples. */
    static final class Stats {
        final long p50;
        final long p95;
        final long p99;
        final long min;
        final long max;
        final double mean;

        private Stats(long p50, long p95, long p99, long min, long max, double mean) {
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.min = min;
            this.max = max;
            this.mean = mean;
        }

        static Stats of(long[] samples) {
            long[] sorted = Arrays.copyOf(samples, samples.length);
            Arrays.sort(sorted);
            long total = 0L;
            for (long v : sorted) {
                total += v;
            }
            return new Stats(
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                sorted[0],
                sorted[sorted.length - 1],
                (double) total / sorted.length);
        }

        /** Nearest-rank: index {@code ceil(p/100 * n) - 1}, clamped into range. */
        private static long percentile(long[] sorted, int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.length);
            return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
        }
    }
}
