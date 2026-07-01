package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.blacklist.BlacklistMatcher;

import java.util.Objects;

/**
 * {@link EventHook} adapter around a {@link BlacklistMatcher}.
 *
 * <p>Returns {@link Decision#DENY} when the action matches any rule loaded
 * from {@code blacklist.txt}, otherwise {@link Decision#PASS} — meaning
 * subsequent hooks and the default-accept path continue as usual.
 *
 * @since 1.1.7 (W3-B6)
 */
public final class BlacklistFileHook implements EventHook {

    private final BlacklistMatcher matcher;

    /**
     * @param matcher precompiled matcher; must not be {@code null}
     */
    public BlacklistFileHook(BlacklistMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    /** @return the wrapped matcher (for diagnostics / hot-swap comparisons) */
    public BlacklistMatcher matcher() {
        return matcher;
    }

    @Override
    public Decision test(Action a) {
        return matcher.matches(a) ? Decision.DENY : Decision.PASS;
    }
}
