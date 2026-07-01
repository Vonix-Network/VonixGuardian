package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.PerWorldConfigStore;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link EventHook} that applies per-world {@link GuardianConfig.Actions}
 * overrides sourced from {@link PerWorldConfigStore} (CoreProtect
 * {@code world_nether.yml} shadow pattern).
 *
 * <p>Invariant: if the world has no override entry, this hook always returns
 * {@link Decision#PASS} — the base {@link EventGate} already enforced the root
 * config, so no further work is needed.
 *
 * <p>If the world <em>has</em> an override, the hook re-evaluates the built-in
 * checks ({@link ActionType} category toggle + {@code worldBlacklist} /
 * {@code blockBlacklist} / {@code sourceBlacklist}) against the OVERRIDDEN view
 * and returns {@link Decision#DENY} if the overridden config would exclude the
 * event. Otherwise it returns {@link Decision#PASS} so subsequent hooks (B6
 * blacklist file, B11 {@link PreLogEvent}) still fire.
 *
 * <p>Never returns {@link Decision#ACCEPT} — an override tightening a rule can
 * drop events but must not bypass downstream hooks that might further veto.
 *
 * @since 1.1.7 (W3-B5)
 */
public final class PerWorldEventHook implements EventHook {

    private final PerWorldConfigStore store;

    public PerWorldEventHook(PerWorldConfigStore store, GuardianConfig.Actions rootActions) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(rootActions, "rootActions");
    }

    @Override
    public Decision test(Action a) {
        GuardianConfig.Actions over = store.overrideFor(a.worldId());
        if (over == null) {
            return Decision.PASS;
        }
        if (!typeEnabled(over, a.type())) {
            return Decision.DENY;
        }
        if (contains(over.worldBlacklist(), a.worldId())) {
            return Decision.DENY;
        }
        if (a.type().category() == ActionType.Category.BLOCK
                && contains(over.blockBlacklist(), a.targetId())) {
            return Decision.DENY;
        }
        if (a.sourceTag() != null && contains(over.sourceBlacklist(), a.sourceTag())) {
            return Decision.DENY;
        }
        return Decision.PASS;
    }

    private static boolean contains(List<String> list, String v) {
        if (list == null || v == null) return false;
        // Small lists (typically a handful of entries per world) — linear scan is fine.
        // Callers who care about hot-path allocation should note this method allocates
        // nothing; the JIT inlines the List.contains for ArrayList backing.
        return list.contains(v);
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

    // Kept only for the (currently unused) case where a future change wants a
    // hot-path Set. Right now blacklists are tiny per-world and List.contains wins.
    @SuppressWarnings("unused")
    private static Set<String> freeze(List<String> src) {
        return src == null ? Set.of() : new HashSet<>(src);
    }
}
