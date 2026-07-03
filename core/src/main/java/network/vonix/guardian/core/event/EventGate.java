package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.diagnostics.MixinHotEventFilter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pure-logic event filter. Decides whether an incoming {@link Action} should
 * be persisted, based on the {@link GuardianConfig.Actions} block plus any
 * registered {@link EventHook}s.
 *
 * <p>Evaluation order:</p>
 * <ol>
 *   <li>Per-type toggles (built-in).</li>
 *   <li>{@code worldBlacklist} / {@code blockBlacklist} / {@code sourceBlacklist} (built-in).</li>
 *   <li>Registered {@link EventHook}s, in insertion order &mdash; first non-{@code PASS} wins.</li>
 * </ol>
 *
 * <p>Hooks are the extension point for per-world config overrides (B5),
 * blacklist.txt file loaders (B6), and the {@link PreLogEvent} cancellable
 * bridge (B11). Register hooks at boot via {@link #addHook(EventHook)}; they
 * live for the entire runtime and are consulted from the hot path.</p>
 *
 * <p>{@code addHook} uses a {@link CopyOnWriteArrayList} so hot-path
 * {@link #shouldLog(Action)} readers never lock and never see torn writes.
 * Registration itself is O(n) but only happens at boot.</p>
 *
 * <p>Stateless-per-action after construction; safe to share across threads.</p>
 *
 * <h2>v1.3.0 W3: internal-event fast-path</h2>
 *
 * <p>Mixin-authored hot-tick events ({@code #fire}, {@code #natural},
 * {@code #dispenser}) are known to be un-vetoable by external policy — no
 * external mod has a legitimate reason to inject into a fire-tick or
 * grass-spread event and cancel it. Sending them through the full hook chain
 * (per-world overrides, blacklist.txt matcher, PreLogEvent bridge) is pure
 * overhead per submit.</p>
 *
 * <p>The fast-path skips the standard hook chain for these action sources but
 * still consults an <em>internal hook</em> list ({@link #addInternalHook}).
 * External code that explicitly opts in — e.g. a per-server rate limiter that
 * wants to see every submit including mixin-authored ones — registers on the
 * internal list. Standard operator hooks (PreLogEvent bridge, per-world
 * overrides, blacklist.txt) sit on the normal list and are transparently
 * bypassed for internal events.</p>
 */
public final class EventGate {

    private final GuardianConfig.Actions cfg;
    private final EnumSet<ActionType> enabledTypes;
    private final Set<String> worldBlacklist;
    private final Set<String> blockBlacklist;
    private final Set<String> sourceBlacklist;
    /**
     * v1.3.0 W2: cached copy of {@code actions.mixinHotEvents} for the built-in
     * kill-switch short-circuit in {@link #shouldLog(Action)}. Set once at
     * construction so hot-path evaluation is a single volatile-free field read
     * instead of a config-record property chase. A config reload rebuilds the
     * {@code EventGate} (see {@code Guardian#reloadConfig}), so this stays
     * consistent with the live config.
     */
    private final boolean mixinHotEventsEnabled;
    private final List<EventHook> hooks = new CopyOnWriteArrayList<>();
    /**
     * v1.3.0 W3: opt-in hook list evaluated for <em>internal</em> events too
     * (mixin-authored fire / natural / dispenser). Standard {@link #hooks}
     * are skipped for internal events; observers that want unconditional
     * visibility register here.
     */
    private final List<EventHook> internalHooks = new CopyOnWriteArrayList<>();

    /** Counter for the internal-fast-path bypass — read from tests / benchmarks. */
    private volatile long internalBypassCount;

    /**
     * @param cfg the actions config block; must not be {@code null}
     */
    public EventGate(GuardianConfig.Actions cfg) {
        if (cfg == null) {
            throw new NullPointerException("cfg");
        }
        this.cfg = cfg;
        this.enabledTypes = enabledTypes(cfg);
        this.worldBlacklist = freeze(cfg.worldBlacklist());
        this.blockBlacklist = freeze(cfg.blockBlacklist());
        this.sourceBlacklist = freeze(cfg.sourceBlacklist());
        this.mixinHotEventsEnabled = cfg.mixinHotEvents();
    }

    private static Set<String> freeze(List<String> src) {
        return src == null ? Set.of() : new HashSet<>(src);
    }

    /**
     * Register a hook consulted after built-in checks. Hooks are evaluated in
     * insertion order; the first non-{@link EventHook.Decision#PASS} decision
     * wins.
     *
     * <p>Thread-safe; may be called before or after {@link Guardian#boot()}.</p>
     *
     * <p><b>v1.3.0 W3:</b> hooks on this list are <em>skipped</em> for internal
     * (mixin-authored) events — see {@link #addInternalHook(EventHook)} for
     * the opt-in observer path.</p>
     *
     * @param hook non-null hook implementation
     * @since 1.1.7
     */
    public void addHook(EventHook hook) {
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        hooks.add(hook);
    }

    /**
     * v1.3.1 X6 (P3-5): soft cap for {@link #addInternalHook(EventHook)} — beyond
     * this count we emit a one-shot WARN and keep accepting registrations. A
     * misbehaving plugin registering per-tick will make {@link #internalHooks}
     * grow unbounded; a soft cap gives operators an early signal without
     * breaking legitimate use.
     */
    public static final int INTERNAL_HOOKS_SOFT_CAP = 32;
    private volatile boolean internalHooksCapWarned;

    /**
     * Register a hook consulted for <em>every</em> action, including internal
     * (mixin-authored) events that bypass the standard {@link #hooks} chain.
     * Same {@code PASS/DENY/ACCEPT} contract as {@link #addHook(EventHook)}.
     *
     * <p>Use this only when the observer genuinely needs to see hot-tick
     * mixin submits; the fast-path exists precisely because standard hooks
     * do not.</p>
     *
     * @since 1.3.0 (W3)
     */
    public void addInternalHook(EventHook hook) {
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        internalHooks.add(hook);
        // v1.3.1 X6 (P3-5): warn once when we cross the soft cap. Not a hard cap —
        // legitimate pipelines may legitimately register more, but 32 hooks on
        // the hot-tick chain is a plugin-registration foot-gun worth surfacing.
        if (!internalHooksCapWarned && internalHooks.size() > INTERNAL_HOOKS_SOFT_CAP) {
            internalHooksCapWarned = true;
            org.slf4j.LoggerFactory.getLogger(EventGate.class).warn(
                "VonixGuardian: internal-hook count exceeded soft cap {} (current {}); "
                + "a plugin may be registering per-tick — inspect /vg status",
                INTERNAL_HOOKS_SOFT_CAP, internalHooks.size());
        }
    }

    /**
     * @return an immutable snapshot of currently registered hooks (order
     *         preserved). For diagnostics / tests only.
     * @since 1.1.7
     */
    public List<EventHook> hooks() {
        return List.copyOf(hooks);
    }

    /**
     * @return an immutable snapshot of currently registered internal hooks.
     *         For diagnostics / tests only.
     * @since 1.3.0 (W3)
     */
    public List<EventHook> internalHooks() {
        return List.copyOf(internalHooks);
    }

    /** @return the number of times the internal fast-path skipped the standard hook chain. */
    public long internalBypassCount() {
        return internalBypassCount;
    }

    /**
     * @param a candidate action; must not be {@code null}
     * @return {@code true} if the action survives all toggles, blacklists,
     *         and hooks; {@code false} to drop silently
     */
    public boolean shouldLog(Action a) {
        if (a == null) {
            return false;
        }
        // v1.3.0 W2: mixin hot-event kill-switch (folded from Guardian.submit).
        // When actions.mixinHotEvents=false, drop any action whose sourceTag was
        // authored by one of the W1a/b/c mixin pipelines ("#fire", "#natural",
        // "#dispenser"). Checked FIRST so operators using the kill-switch don't
        // pay type-check + blacklist-lookup + hook-chain traversal per mixin
        // event during a load-shedding event.
        String sourceTag = a.sourceTag();
        boolean mixinSourced = MixinHotEventFilter.isMixinSourced(sourceTag);
        if (!mixinHotEventsEnabled && mixinSourced) {
            return false;
        }
        if (!typeEnabled(a.type())) {
            return false;
        }
        if (worldBlacklist.contains(a.worldId())) {
            return false;
        }
        ActionType t = a.type();
        if (t.category() == ActionType.Category.BLOCK && blockBlacklist.contains(a.targetId())) {
            return false;
        }
        if (sourceTag != null && sourceBlacklist.contains(sourceTag)) {
            return false;
        }
        // v1.3.0 W3: internal-event fast-path. Mixin-authored events are known
        // to be un-vetoable by external policy; skip the standard hook chain
        // entirely and only run the opt-in internalHooks list.
        List<EventHook> chain = mixinSourced ? internalHooks : hooks;
        if (mixinSourced) {
            internalBypassCount++;
        }
        // Hook chain: first opinionated hook wins.
        for (EventHook hook : chain) {
            EventHook.Decision d = hook.test(a);
            if (d == EventHook.Decision.ACCEPT) return true;
            if (d == EventHook.Decision.DENY) return false;
            // PASS: continue
        }
        return true;
    }

    private boolean typeEnabled(ActionType t) {
        return t != null && enabledTypes.contains(t);
    }

    private static EnumSet<ActionType> enabledTypes(GuardianConfig.Actions cfg) {
        EnumSet<ActionType> out = EnumSet.noneOf(ActionType.class);
        for (ActionType t : ActionType.values()) {
            if (typeEnabled(cfg, t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean typeEnabled(GuardianConfig.Actions cfg, ActionType t) {
        return switch (t.category()) {
            case BLOCK -> cfg.logBlocks();
            case CONTAINER -> cfg.logContainers();
            case ITEM -> cfg.logItems();
            case ENTITY -> cfg.logEntities();
            case WORLD -> (t == ActionType.EXPLOSION) ? cfg.logExplosions() : cfg.logWorldEvents();
            case MESSAGE -> switch (t) {
                case CHAT -> cfg.logChat();
                case COMMAND -> cfg.logCommands();
                case SIGN -> cfg.logSigns();
                default -> true;
            };
            case SESSION -> cfg.logSessions();
            case INTERACT -> cfg.logInteractions();
        };
    }
}
