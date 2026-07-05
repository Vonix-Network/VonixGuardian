package network.vonix.guardian.core.diagnostics;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.api.GuardianAPI;
import network.vonix.guardian.core.event.EventHook;
import network.vonix.guardian.core.storage.Schema;

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
 * on-demand, in a structured multi-line report.
 *
 * <p><b>Sections</b>, each self-titled:
 * <ol>
 *   <li>Version</li>
 *   <li>Storage: backend + schema version + connection health</li>
 *   <li>Queue: consumer state, depth, submitted, gated, dropped, per-type histogram</li>
 *   <li>Coalescer state (if enabled)</li>
 *   <li>Hook chain (list registered hooks in order)</li>
 *   <li>Per-world overrides (count + world list, if any)</li>
 *   <li>Blacklist file rule count (if loaded)</li>
 *   <li>Auto-purge state (if enabled)</li>
 *   <li>Permissions: LP detected, default op-level, per-node overrides</li>
 *   <li>Public API version</li>
 * </ol>
 *
 * <p>Output is a {@code List<String>} of lines; cells wrap each with their
 * platform's chat renderer. No colors baked in (theming happens cell-side).
 * Lines starting with {@code "§ "} are section headers; cells may render
 * them differently (bold, colored, etc.).
 *
 * @since 1.1.7
 */
public final class GuardianStatus {

    private GuardianStatus() {}

    /**
     * Render the full status report. Never throws; every subsystem is wrapped
     * in a try/catch so a broken accessor produces a single {@code (err)} line
     * rather than aborting the whole report.
     *
     * @param g the live Guardian instance
     * @return ordered list of report lines (no trailing newlines)
     */
    public static List<String> render(Guardian g) {
        List<String> out = new ArrayList<>();

        // ---- 1. Header + version ----
        section(out, "VonixGuardian");
        line(out, "  version    " + GuardianAPI.PLUGIN_VERSION);

        // ---- 2. Storage ----
        section(out, "Storage");
        line(out, "  backend    " + safe(() -> g.config().database().type()));
        line(out, "  schema     v" + Schema.CURRENT_VERSION);
        line(out, "  health     " + safe(() -> g.dao().isHealthy() ? "OK" : "UNHEALTHY"));

        // ---- 3. Queue ----
        section(out, "Writer queue");
        line(out, "  consumer   " + safe(() -> g.queue().isPaused() ? "PAUSED" : "running"));
        line(out, "  depth      " + safe(() -> Integer.toString(g.queue().depth()))
                + " / " + safe(() -> Integer.toString(g.config().queue().maxSize())));
        line(out, "  submitted  " + g.submitted());
        line(out, "  gated      " + g.gated());
        line(out, "  dropped    " + safe(() -> Long.toString(g.queue().dropped())));
        line(out, "  sinkDrops  " + safe(() -> Long.toString(g.queue().permanentlyDropped())));
        Map<String, Long> submittedByType = safeMap(() -> g.queue().submittedByTypeSnapshot());
        if (!submittedByType.isEmpty()) {
            line(out, "  by-type    " + formatHistogram(submittedByType));
        }
        Map<String, Long> droppedByType = safeMap(() -> g.queue().droppedByTypeSnapshot());
        if (!droppedByType.isEmpty()) {
            line(out, "  drops      " + formatHistogram(droppedByType));
        }

        // ---- 4. Coalescer ----
        try {
            long coalWin = g.config().actions().entityBlockChangeCoalesceWindowMs();
            if (coalWin > 0) {
                section(out, "Coalescer");
                line(out, "  window     " + coalWin + "ms");
                line(out, "  maxTracked " + g.config().actions().entityBlockChangeMaxTracked());
                var coal = g.entityBlockCoalescer();
                line(out, "  active     " + (coal != null ? coal.size() : 0));
                if (coal != null) {
                    line(out, "  suppressed " + coal.hits());
                    line(out, "  passed     " + coal.misses());
                    line(out, "  evictions  " + coal.evictions());
                    line(out, "  capDrops   " + coal.capDrops());
                }
            }
        } catch (Throwable ignored) { }

        // ---- 4b. Mixin hot events (v1.3.0 W4) ----
        // Shows the operator kill-switch state (actions.mixinHotEvents) and the
        // per-type submit rate for the eight hot-tick mixin-sourced ActionTypes.
        // Rates come from the sliding-window meter in BatchedAsyncWriteQueue.
        try {
            section(out, "Mixin hot events");
            boolean mixinOn = g.config().actions().mixinHotEvents();
            line(out, "  enabled    " + (mixinOn ? "yes" : "no (kill-switch engaged)"));
            line(out, "  allocRate  " + String.format(java.util.Locale.ROOT, "%.2f/s",
                    g.queue().allocationRatePerSecond()));
            Map<String, Double> rates = safeRateMap(() -> g.queue().submitRateByType());
            String[] mixinTypes = {
                "BURN", "SPREAD", "IGNITE", "FADE", "FORM",
                "LEAVES_DECAY", "DISPENSE", "ENTITY_CHANGE_BLOCK"
            };
            for (String t : mixinTypes) {
                double r = rates.getOrDefault(t, 0.0);
                line(out, "  " + padRight(t, 20) + " "
                        + String.format(java.util.Locale.ROOT, "%.2f/s", r));
            }
        } catch (Throwable t) {
            line(out, "  (err: " + t.getClass().getSimpleName() + ")");
        }

        // ---- 5. Hook chain ----
        section(out, "Event hooks");
        try {
            List<EventHook> hooks = g.gate().hooks();
            if (hooks.isEmpty()) {
                line(out, "  (none registered)");
            } else {
                for (int i = 0; i < hooks.size(); i++) {
                    line(out, "  #" + (i + 1) + "         " + hooks.get(i).getClass().getSimpleName());
                }
            }
            // v1.3.1 X6 (P3-5): surface the internal-hook count so operators can spot
            // misbehaving plugin registrations against the mixin-hot-event chain.
            int internalCount = g.gate().internalHooks().size();
            String capNote = internalCount > network.vonix.guardian.core.event.EventGate.INTERNAL_HOOKS_SOFT_CAP
                ? " (over soft cap " + network.vonix.guardian.core.event.EventGate.INTERNAL_HOOKS_SOFT_CAP + ")"
                : "";
            line(out, "  internal   " + internalCount + capNote);
        } catch (Throwable t) {
            line(out, "  (err: " + t.getClass().getSimpleName() + ")");
        }

        // ---- 6. Per-world overrides ----
        try {
            var store = g.perWorldStore();
            if (store != null) {
                section(out, "Per-world overrides");
                var worlds = store.overriddenWorlds();
                line(out, "  count      " + worlds.size());
                if (!worlds.isEmpty()) {
                    line(out, "  worlds     " + worlds);
                }
            }
        } catch (Throwable ignored) { }

        // ---- 7. Blacklist file ----
        try {
            var bl = g.blacklistHook();
            if (bl != null) {
                section(out, "Blacklist file");
                line(out, "  rules      " + bl.matcher().size());
            }
        } catch (Throwable ignored) { }

        // ---- 8. Auto-purge ----
        try {
            var ap = g.autoPurgeScheduler();
            if (ap != null && ap.isEnabled()) {
                section(out, "Auto-purge");
                line(out, "  enabled    yes (keep " + ap.retentionSeconds() + "s)");
                line(out, "  purged     " + ap.getRowsPurgedSinceRestart() + " rows this restart");
            }
        } catch (Throwable ignored) { }

        // ---- 9. Permissions ----
        section(out, "Permissions");
        line(out, "  default-op " + safe(() -> Integer.toString(g.config().permissions().defaultOpLevel())));
        try {
            var perms = g.config().permissions();
            var overrides = perms.perNodeOpLevelsOrEmpty();
            if (overrides != null && !overrides.isEmpty()) {
                line(out, "  overrides  " + overrides.size() + " per-node");
            }
        } catch (Throwable ignored) { }

        // ---- 10. Public API ----
        section(out, "Public API");
        line(out, "  version    v" + GuardianAPI.API_VERSION);

        return out;
    }

    // ---- helpers ----

    private static void section(List<String> out, String title) {
        out.add("§ " + title);
    }

    private static void line(List<String> out, String s) {
        out.add(s);
    }

    @FunctionalInterface
    private interface Supplier<T> {
        T get() throws Throwable;
    }

    private static String safe(Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "(null)" : v;
        } catch (Throwable t) {
            return "(err: " + t.getClass().getSimpleName() + ")";
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

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }

    /** Compact histogram: sorted desc by count, top 6 entries plus tail. */
    private static String formatHistogram(Map<String, Long> map) {
        var sorted = new ArrayList<>(map.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        long tail = 0L;
        int tailCount = 0;
        for (var e : sorted) {
            if (shown < 6) {
                if (shown > 0) sb.append(", ");
                sb.append(e.getKey()).append('=').append(e.getValue());
                shown++;
            } else {
                tail += e.getValue();
                tailCount++;
            }
        }
        if (tail > 0) {
            sb.append(", (+").append(tailCount).append(" others=").append(tail).append(')');
        }
        return sb.toString();
    }
}
