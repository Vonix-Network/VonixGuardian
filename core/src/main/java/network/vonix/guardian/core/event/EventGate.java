package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;

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
 */
public final class EventGate {

    private final GuardianConfig.Actions cfg;
    private final EnumSet<ActionType> enabledTypes;
    private final Set<String> worldBlacklist;
    private final Set<String> blockBlacklist;
    private final Set<String> sourceBlacklist;
    private final List<EventHook> hooks = new CopyOnWriteArrayList<>();

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
     * @return an immutable snapshot of currently registered hooks (order
     *         preserved). For diagnostics / tests only.
     * @since 1.1.7
     */
    public List<EventHook> hooks() {
        return List.copyOf(hooks);
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
        if (a.sourceTag() != null && sourceBlacklist.contains(a.sourceTag())) {
            return false;
        }
        // Hook chain: first opinionated hook wins.
        for (EventHook hook : hooks) {
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
