package network.vonix.guardian.core.diagnostics;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.api.GuardianAPI;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.EventGate;
import network.vonix.guardian.core.event.EventHook;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.Schema;
import network.vonix.guardian.core.storage.dbmigrate.RawJdbcAccess;
import network.vonix.guardian.core.theme.ThemeRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical {@code /vg status} renderer &mdash; the SINGLE diagnostic surface,
 * matching CoreProtect's {@code /co status} contract.
 *
 * <p><b>Design principle:</b> CoreProtect exposes exactly one on-demand
 * diagnostic ({@code /co status}) plus one pause control ({@code /co consumer}).
 * VonixGuardian matches that shape: no {@code /vg debug} subcommand tree, no
 * always-on log spam. Everything an operator needs to see is here, produced
 * on-demand, in a structured report.</p>
 *
 * <p><b>One screen, nothing hidden.</b> The report used to run to 62 lines on a
 * healthy server: eight rows of {@code 0.00/s}, one row per counter per line,
 * and a {@code [VonixGuardian]} prefix on every line. That pushed the lines
 * that mattered off the chat screen. The fix is compression, not amputation
 * &mdash; counters that answer one question share a row, and the per-type
 * breakdown and per-batch detail are still printed when they exist. A report
 * an operator cannot read is not a diagnostic; a report that hides a failure is
 * worse.</p>
 *
 * <p><b>Sections:</b>
 * <ol>
 *   <li>Identity &mdash; version, API, applied theme</li>
 *   <li>Storage &mdash; backend, stamped schema version, reachability</li>
 *   <li>Writer &mdash; consumer, maintenance, depth, counters, durability, mixin gate, coalescer</li>
 *   <li>Recovery &mdash; open rollback batches, rows needing repair, and what to do about them</li>
 *   <li>Configuration &mdash; hook chain, auto-purge, permissions, per-world overrides</li>
 * </ol>
 *
 * <p><b>Honesty rules this class obeys:</b>
 * <ul>
 *   <li>No row claims more than it checked. {@code /vg status} never writes, so
 *       the row is {@code reachable} (a trivial query answered) and there is no
 *       {@code health} row implying lock, disk-space, and permission testing
 *       that the probe never performs.</li>
 *   <li>{@code schema} reads {@code vg_schema_version} and compares it with the
 *       version this jar creates, so drift is named rather than assumed. Drift
 *       is the one storage fault a read-only probe can actually prove.</li>
 *   <li>{@code theme} says so when the configured name is not a registered
 *       theme. A typo used to resolve to the default silently, so the setting
 *       looked like it simply did nothing.</li>
 *   <li>A {@code null} or blank value renders as {@code (not set)}, never as
 *       the word {@code null}.</li>
 *   <li>Every label is padded to {@link #LABEL_WIDTH} by {@link #row}, so the
 *       value column is one column. Hand-padded labels at six different widths
 *       were the mechanical reason this report could not be scanned.</li>
 *   <li>Warnings ({@code RETENTION LIMIT REACHED}, {@code durable quarantine
 *       disabled}, schema drift) appear only when true. A caveat printed on
 *       every healthy server trains the operator to skip the row.</li>
 * </ul>
 *
 * <p>Output is a {@code List<String>} of lines; cells wrap each with their
 * platform's chat renderer. No colors baked in (theming happens cell-side).
 * Lines starting with {@code "§ "} are section headers; cells may render
 * them differently (bold, colored, etc.).
 *
 * @since 1.1.7
 */
public final class GuardianStatus {

    /** Width every label is padded to, so all values start in the same column. */
    static final int LABEL_WIDTH = 15;

    /** Marker for a row whose value genuinely does not exist. Never "null". */
    static final String NOT_SET = "(not set)";

    /** How many list entries are named inline before the remainder is summed. */
    static final int INLINE_LIST_LIMIT = 3;

    /** Histogram entries named inline before the tail is summed. */
    private static final int HISTOGRAM_TOP = 4;

    private GuardianStatus() {}

    private static String batchMode(int mode) {
        if (mode == 0) return "ROLLBACK";
        if (mode == 1) return "RESTORE";
        return "mode=" + mode;
    }

    /**
     * Render the full status report. Never throws; every subsystem is wrapped
     * so a broken accessor produces one labelled row rather than aborting the
     * whole report.
     *
     * @param g the live Guardian instance
     * @return ordered list of report lines (no trailing newlines)
     */
    public static List<String> render(Guardian g) {
        List<String> out = new ArrayList<>();

        // One sentence before the detail blocks. The rest of the report stays
        // available; this is the line an operator can read without scrolling.
        line(out, "Status: " + safe(() -> String.valueOf(g.config().database().type()))
            + " storage, writer "
            + safe(() -> g.queue().isPaused() ? "paused" : "running")
            + ", "
            + safe(() -> g.queue().depth() + " of " + g.config().queue().maxSize())
            + " waiting.");

        renderIdentity(out, g);
        renderStorage(out, g);
        renderWriter(out, g);
        renderRecovery(out, g);
        renderConfiguration(out, g);

        return out;
    }

    // ---- 1. identity ----

    private static void renderIdentity(List<String> out, Guardian g) {
        // Title, not the product name: cells prefix the first section with
        // "[VonixGuardian] ", and a section called "VonixGuardian" would render
        // as "[VonixGuardian] VonixGuardian".
        section(out, "Version");
        row(out, "plugin", GuardianAPI.PLUGIN_VERSION);
        row(out, "api", "v" + GuardianAPI.API_VERSION);
        row(out, "theme", themeState(g));
    }

    /**
     * Theme row. A configured name that is not registered resolves to the
     * default &mdash; correct for a renderer that must never hand out null, but
     * it used to make a typo in {@code config.json} indistinguishable from a
     * working setting. Say which name was ignored.
     */
    static String themeState(Guardian g) {
        try {
            ThemeRegistry.Resolution r = ThemeRegistry.resolve(g.config().theme());
            if (r.honoured()) {
                return r.theme().name();
            }
            return r.theme().name() + " (requested \"" + r.requestedOrPlaceholder()
                    + "\" is not a theme; the default is in use)";
        } catch (Throwable t) {
            return errorName(t);
        }
    }

    // ---- 2. storage ----

    private static void renderStorage(List<String> out, Guardian g) {
        section(out, "Storage");
        row(out, "backend", safe(() -> String.valueOf(g.config().database().type())));
        row(out, "schema", schemaState(g));
        // The row used to be called "health" and was SELECT 1. It cannot see a
        // locked table, a full disk, or a permission failure, so it must not be
        // labelled health.
        row(out, "reachable", safe(() -> g.dao().isHealthy()
                ? "yes (SELECT 1 answered)"
                : "NO — a trivial query did not answer; writes are at risk"));
    }

    /**
     * Schema row: reads the stamped version and compares it with the version
     * this jar creates, so drift is reported instead of assumed. This is the
     * one storage fault a read-only status command can actually detect.
     */
    static String schemaState(Guardian g) {
        if (!(g.dao() instanceof RawJdbcAccess raw)) {
            return "v" + Schema.CURRENT_VERSION + " expected (this backend has no version probe)";
        }
        final int found;
        try {
            found = raw.<Integer>withRawConnection(GuardianStatus::readStampedVersion);
        } catch (Throwable t) {
            return "UNREADABLE — " + errorName(t);
        }
        return schemaState(found, Schema.CURRENT_VERSION);
    }

    /**
     * The schema row's text for a stamped version. Split out so every branch
     * &mdash; current, behind, ahead, unstamped &mdash; can be proven without
     * hand-editing a live database's version table.
     *
     * @param stamped  the version read from {@code vg_schema_version}; {@code <= 0} when unstamped
     * @param expected {@link Schema#CURRENT_VERSION} for this jar
     * @return one row naming the version and any drift
     */
    static String schemaState(int stamped, int expected) {
        if (stamped <= 0) {
            return "unstamped (this jar expects v" + expected + ")";
        }
        if (stamped == expected) {
            return "v" + stamped + " (current)";
        }
        if (stamped < expected) {
            return "v" + stamped + " BEHIND v" + expected + " — restart to apply migrations";
        }
        return "v" + stamped + " AHEAD of this jar (v" + expected
                + ") — written by a newer build";
    }

    private static int readStampedVersion(Connection c) throws SQLException {
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM vg_schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ---- 3. writer ----

    private static void renderWriter(List<String> out, Guardian g) {
        section(out, "Writer");
        row(out, "consumer", safe(() -> g.queue().isPaused() ? "PAUSED" : "running"));
        row(out, "maintenance", safe(() -> g.isMaintenanceWriteBlocked()
                ? "WRITE-BLOCKED" : "normal"));
        row(out, "depth", safe(() -> g.queue().depth() + " of " + g.config().queue().maxSize()));
        row(out, "counters", "submitted=" + g.submitted()
                + " gated=" + g.gated()
                + " dropped=" + safe(() -> Long.toString(g.queue().dropped())));
        row(out, "durability", durabilitySummary(g));
        row(out, "mixin events", mixinState(g));
        row(out, "coalescer", coalescerState(g));
        // Only when rows actually arrived; an empty histogram is a row that says
        // "nothing has been logged since boot", which the counters row already
        // implies.
        Map<String, Long> submittedByType = safeMap(() -> g.queue().submittedByTypeSnapshot());
        if (!submittedByType.isEmpty()) {
            row(out, "logged types", formatHistogram(submittedByType));
        }
        Map<String, Long> droppedByType = safeMap(() -> g.queue().droppedByTypeSnapshot());
        if (!droppedByType.isEmpty()) {
            row(out, "dropped types", formatHistogram(droppedByType));
        }
    }

    /**
     * One row for every sink counter. Zeros are kept: a zero here means "nothing
     * was lost", which is the answer to the question this report exists to
     * answer. The retention warnings appear only when they are true, instead of
     * as a parenthetical on every healthy server.
     */
    static String durabilitySummary(Guardian g) {
        long sinkDrops;
        long quarantined;
        long recovered;
        long qOverflow;
        long qWriteFail;
        boolean retentionLimitReached;
        boolean quarantineEnabled;
        try {
            sinkDrops = g.queue().permanentlyDropped();
            quarantined = g.queue().quarantined();
            recovered = g.queue().recoveredFromQuarantine();
            qOverflow = g.queue().quarantineOverflow();
            qWriteFail = g.queue().quarantineWriteFailures();
            retentionLimitReached = g.queue().quarantineRetentionLimitReached();
            quarantineEnabled = g.queue().quarantineEnabled();
        } catch (Throwable t) {
            return errorName(t);
        }
        return durabilityLine(sinkDrops, quarantined, recovered, qOverflow, qWriteFail,
                retentionLimitReached, quarantineEnabled);
    }

    /**
     * The durability row's text, isolated from the queue so the warning
     * conditions can be proven without inducing a real sink failure.
     *
     * @param sinkDrops             rows lost for good (the sink refused them)
     * @param quarantined           rows parked in quarantine
     * @param recovered             rows taken back out of quarantine
     * @param qOverflow             rows dropped because quarantine was full
     * @param qWriteFail            rows dropped because quarantine could not be written
     * @param retentionLimitReached quarantine hit its retention cap
     * @param quarantineEnabled     whether durable quarantine is on at all
     * @return one row of {@code name=value} counters plus any warning that is true
     */
    static String durabilityLine(long sinkDrops, long quarantined, long recovered,
                                 long qOverflow, long qWriteFail,
                                 boolean retentionLimitReached, boolean quarantineEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("sinkDrops=").append(sinkDrops)
          .append(" quarantined=").append(quarantined)
          .append(" recovered=").append(recovered)
          .append(" qOverflow=").append(qOverflow)
          .append(" qWriteFail=").append(qWriteFail);
        if (retentionLimitReached) {
            sb.append(" — RETENTION LIMIT REACHED; failed rows are not durably retained");
        }
        if (!quarantineEnabled) {
            sb.append(" — durable quarantine disabled");
        } else if (qWriteFail > 0L) {
            sb.append(" — quarantine write failures are not durably retained");
        }
        return sb.toString();
    }

    /**
     * Gate state, allocation rate, and the per-type rate of each hot-tick
     * mixin source.
     *
     * <p>Every one of the eight types is still named, so an operator can see
     * that a source is idle rather than nonexistent. What changed is the
     * shape: eight {@code 0.00/s} lines became one row naming the idle group,
     * which is where most of the old report's length came from. Types that are
     * actually producing are called out individually.</p>
     */
    static String mixinState(Guardian g) {
        try {
            if (!g.config().actions().mixinHotEvents()) {
                return "OFF (kill-switch engaged; no mixin-sourced rows are recorded)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("on, alloc ").append(rate(g.queue().allocationRatePerSecond()));
            Map<String, Double> rates = safeRateMap(() -> g.queue().submitRateByType());
            String[] mixinTypes = {
                "BURN", "SPREAD", "IGNITE", "FADE", "FORM",
                "LEAVES_DECAY", "DISPENSE", "ENTITY_CHANGE_BLOCK"
            };
            List<String> active = new ArrayList<>(mixinTypes.length);
            List<String> idle = new ArrayList<>(mixinTypes.length);
            for (String t : mixinTypes) {
                double r = rates.getOrDefault(t, 0.0);
                if (r > 0.0) {
                    active.add(t + " " + rate(r));
                } else {
                    idle.add(t);
                }
            }
            if (active.isEmpty()) {
                sb.append("; all eight types idle (").append(String.join(", ", idle)).append(')');
            } else {
                sb.append("; active ").append(String.join(", ", active));
                if (!idle.isEmpty()) {
                    sb.append("; idle ").append(String.join(", ", idle));
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return errorName(t);
        }
    }

    static String coalescerState(Guardian g) {
        try {
            long window = g.config().actions().entityBlockChangeCoalesceWindowMs();
            if (window <= 0) {
                return "off (window 0)";
            }
            var coal = g.entityBlockCoalescer();
            if (coal == null) {
                return "window " + window + "ms, not running";
            }
            return "window " + window
                    + "ms, tracked=" + coal.size()
                    + "/" + g.config().actions().entityBlockChangeMaxTracked()
                    + " suppressed=" + coal.hits()
                    + " passed=" + coal.misses()
                    + " evictions=" + coal.evictions()
                    + " capDrops=" + coal.capDrops();
        } catch (Throwable t) {
            return errorName(t);
        }
    }

    private static String rate(double perSecond) {
        return String.format(java.util.Locale.ROOT, "%.2f/s", perSecond);
    }

    // ---- 4. recovery ----

    private static void renderRecovery(List<String> out, Guardian g) {
        section(out, "Recovery");
        List<GuardianDao.OpenRollbackBatch> open = null;
        List<GuardianDao.RepairRequired> repairs = null;
        try {
            open = g.dao().findOpenRollbackBatches(20);
        } catch (Throwable t) {
            row(out, "open batches", errorName(t));
        }
        try {
            repairs = g.dao().findRepairRequired();
        } catch (Throwable t) {
            row(out, "repair required", errorName(t));
        }

        // Only claim "none" when the query actually answered. A failed query that
        // also prints "none" tells the operator two contradictory things.
        if (open != null) {
            if (open.isEmpty()) {
                row(out, "open batches", "none");
            } else {
                row(out, "open batches", describeBatches(open));
                // Boot does not replay these; the operator decides.
                line(out, "    inspect with /vg lookup before re-running rollback or restore;"
                        + " /vg batch close <id> marks one finished and does not change the world");
            }
        }

        if (repairs != null) {
            row(out, "repair required",
                    repairs.isEmpty() ? "none" : describeRepairs(repairs));
        }
    }

    private static String describeBatches(List<GuardianDao.OpenRollbackBatch> open) {
        int shown = Math.min(INLINE_LIST_LIMIT, open.size());
        List<String> names = new ArrayList<>(shown + 1);
        for (int i = 0; i < shown; i++) {
            GuardianDao.OpenRollbackBatch b = open.get(i);
            names.add("batch " + b.id() + " mode=" + batchMode(b.mode())
                    + " affected=" + b.affected());
        }
        if (open.size() > shown) {
            names.add("+" + (open.size() - shown) + " more");
        }
        return String.join(", ", names);
    }

    private static String describeRepairs(List<GuardianDao.RepairRequired> repairs) {
        int shown = Math.min(INLINE_LIST_LIMIT, repairs.size());
        List<String> names = new ArrayList<>(shown + 1);
        for (int i = 0; i < shown; i++) {
            GuardianDao.RepairRequired r = repairs.get(i);
            names.add("repair action " + r.actionId()
                    + " batch=" + r.batchId()
                    + " reason=" + blankTo(r.reason(), NOT_SET));
        }
        if (repairs.size() > shown) {
            names.add("+" + (repairs.size() - shown) + " more");
        }
        return String.join(", ", names);
    }

    // ---- 5. configuration ----

    private static void renderConfiguration(List<String> out, Guardian g) {
        section(out, "Configuration");
        row(out, "hooks", safe(() -> hookSummary(g)));
        row(out, "auto-purge", safe(() -> autoPurgeState(g)));
        row(out, "permissions", safe(() -> permissionSummary(g)));
        row(out, "worlds", safe(() -> worldSummary(g)));
        row(out, "nbt capture", safe(() -> g.config().storage().persistNbt()
                ? "on"
                : "OFF (rollback refuses rows without a full payload)"));
        row(out, "blacklist", safe(() -> {
            var bl = g.blacklistHook();
            return bl == null ? "file not loaded" : bl.matcher().size() + " rules loaded";
        }));
    }

    static String hookSummary(Guardian g) {
        List<EventHook> hooks = g.gate().hooks();
        int internal = g.gate().internalHooks().size();
        String list;
        if (hooks.isEmpty()) {
            list = "none registered";
        } else {
            List<String> names = new ArrayList<>(hooks.size());
            for (EventHook hook : hooks) {
                names.add(hook.getClass().getSimpleName());
            }
            list = hooks.size() + " (" + String.join(", ", names) + ")";
        }
        String over = internal > EventGate.INTERNAL_HOOKS_SOFT_CAP
                ? " — OVER soft cap " + EventGate.INTERNAL_HOOKS_SOFT_CAP
                : "";
        return list + ", " + internal + " internal" + over;
    }

    static String autoPurgeState(Guardian g) {
        var ap = g.autoPurgeScheduler();
        if (ap != null && ap.isEnabled()) {
            return "on, keep " + ap.retentionSeconds() + "s, "
                    + ap.getRowsPurgedSinceRestart() + " rows this restart";
        }
        GuardianConfig cfg = g.config();
        if (cfg.purge() != null && cfg.purge().autoPurgeSeconds() == 0L) {
            return "off (0 = unbounded; daemon does not run; suggested "
                    + GuardianConfig.SUGGESTED_BUSY_SERVER_AUTO_PURGE_SECONDS
                    + "s for a busy server, not applied)";
        }
        return "not configured";
    }

    static String permissionSummary(Guardian g) {
        var perms = g.config().permissions();
        var overrides = perms.perNodeOpLevelsOrEmpty();
        return "default-op " + perms.defaultOpLevel()
                + ", " + (overrides == null ? 0 : overrides.size()) + " per-node overrides"
                + ", LuckPerms " + (perms.useLuckPerms() ? "on" : "off");
    }

    static String worldSummary(Guardian g) {
        var store = g.perWorldStore();
        if (store == null) {
            return "no per-world store";
        }
        var worlds = store.overriddenWorlds();
        if (worlds == null || worlds.isEmpty()) {
            return "no overrides";
        }
        return worlds.size() + " overridden (" + worlds + ")";
    }

    // ---- helpers ----

    private static void section(List<String> out, String title) {
        out.add("§ " + title);
    }

    private static void line(List<String> out, String s) {
        out.add(s);
    }

    /**
     * One key/value row. The label is padded to {@link #LABEL_WIDTH} and always
     * followed by one space, so every value in the report starts in the same
     * column &mdash; including when a label is exactly {@link #LABEL_WIDTH}
     * characters long and {@code padRight} adds nothing. A {@code null} or blank
     * value renders as {@link #NOT_SET} and never as the word "null".
     */
    static void row(List<String> out, String label, String value) {
        out.add("  " + padRight(blankTo(label, "?"), LABEL_WIDTH) + ' '
                + blankTo(value, NOT_SET));
    }

    static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String errorName(Throwable t) {
        if (t == null) {
            return "error (unknown)";
        }
        String name = t.getClass().getSimpleName();
        return "error (" + (name == null || name.isBlank() ? "unknown" : name) + ")";
    }

    @FunctionalInterface
    private interface Supplier<T> {
        T get() throws Throwable;
    }

    private static String safe(Supplier<String> s) {
        try {
            return blankTo(s.get(), NOT_SET);
        } catch (Throwable t) {
            return errorName(t);
        }
    }

    private static Map<String, Long> safeMap(Supplier<Map<String, Long>> s) {
        try {
            Map<String, Long> v = s.get();
            return v == null ? Map.of() : v;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    private static Map<String, Double> safeRateMap(Supplier<Map<String, Double>> s) {
        try {
            Map<String, Double> v = s.get();
            return v == null ? Map.of() : v;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    static String padRight(String s, int width) {
        if (s == null) return "";
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }

    /** Compact histogram: sorted desc by count, top entries plus a summed tail. */
    static String formatHistogram(Map<String, Long> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        var sorted = new ArrayList<>(map.entrySet());
        sorted.sort((a, b) -> Long.compare(
                b.getValue() == null ? 0L : b.getValue(),
                a.getValue() == null ? 0L : a.getValue()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        long tail = 0L;
        int tailCount = 0;
        for (var e : sorted) {
            long value = e.getValue() == null ? 0L : e.getValue();
            if (shown < HISTOGRAM_TOP) {
                if (shown > 0) sb.append(", ");
                sb.append(blankTo(e.getKey(), "unknown")).append('=').append(value);
                shown++;
            } else {
                tail += value;
                tailCount++;
            }
        }
        if (tailCount > 0) {
            sb.append(", (+").append(tailCount).append(" others=").append(tail).append(')');
        }
        return sb.toString();
    }
}
