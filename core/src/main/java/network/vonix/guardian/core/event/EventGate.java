package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-logic event filter. Decides whether an incoming {@link Action} should
 * be persisted, based on the {@link GuardianConfig.Actions} block:
 *
 * <ul>
 *   <li><b>Per-type toggles</b> ({@code logBlocks}, {@code logContainers},
 *       {@code logItems}, {@code logEntities}, {@code logExplosions},
 *       {@code logChat}, {@code logCommands}, {@code logSessions},
 *       {@code logSigns}) — when {@code false}, every action of that family
 *       is dropped.</li>
 *   <li><b>{@code worldBlacklist}</b> — exact-match against {@link Action#worldId()}.</li>
 *   <li><b>{@code blockBlacklist}</b> — exact-match against {@link Action#targetId()}
 *       for the block-shaped action types ({@code BLOCK_PLACE}, {@code BLOCK_BREAK}).</li>
 *   <li><b>{@code sourceBlacklist}</b> — exact-match against {@link Action#sourceTag()};
 *       a {@code null} source never matches.</li>
 * </ul>
 *
 * <p>Stateless after construction; safe to share across threads.</p>
 */
public final class EventGate {

    private final GuardianConfig.Actions cfg;
    private final Set<String> worldBlacklist;
    private final Set<String> blockBlacklist;
    private final Set<String> sourceBlacklist;

    /**
     * @param cfg the actions config block; must not be {@code null}
     */
    public EventGate(GuardianConfig.Actions cfg) {
        if (cfg == null) {
            throw new NullPointerException("cfg");
        }
        this.cfg = cfg;
        this.worldBlacklist = freeze(cfg.worldBlacklist());
        this.blockBlacklist = freeze(cfg.blockBlacklist());
        this.sourceBlacklist = freeze(cfg.sourceBlacklist());
    }

    private static Set<String> freeze(List<String> src) {
        return src == null ? Set.of() : new HashSet<>(src);
    }

    /**
     * @param a candidate action; must not be {@code null}
     * @return {@code true} if the action survives all toggles and blacklists
     *         and should be enqueued + logged; {@code false} to drop silently
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
        return true;
    }

    private boolean typeEnabled(ActionType t) {
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
