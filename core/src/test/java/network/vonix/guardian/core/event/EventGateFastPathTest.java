package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W3 — regression test for the {@link EventGate} internal-event
 * fast-path.
 *
 * <p>Contract: for actions whose {@code sourceTag} starts with one of the
 * reserved mixin prefixes ({@code #fire}, {@code #natural}, {@code #dispenser}),
 * the standard hook chain is skipped and only the opt-in
 * {@code internalHooks} list runs. Non-mixin actions still traverse the
 * standard chain.</p>
 */
class EventGateFastPathTest {

    private static GuardianConfig.Actions defaultActions() {
        return new GuardianConfig.Actions(
            true, true, true, true, true, true, true, true, true, true, true,
            List.of(), List.of(), List.of(),
            60_000L, 512,
            List.of(), false,
            true, true, true, true, true, false, false, true, true, false, true, false,
            true
        ,
        true);
    }

    private static Action action(ActionType type, String sourceTag) {
        return new Action(-1L, System.currentTimeMillis(), type,
            UUID.randomUUID(), "tester", "minecraft:overworld",
            0, 64, 0, "minecraft:oak_log", null, 1, false, sourceTag);
    }

    /** Counting hook that never denies; used to prove which chain ran. */
    private static final class CountingHook implements EventHook {
        final AtomicInteger seen = new AtomicInteger();
        @Override public Decision test(Action a) {
            seen.incrementAndGet();
            return Decision.PASS;
        }
    }

    @Test
    void mixinSourced_skipsStandardHookChain() {
        EventGate gate = new EventGate(defaultActions());
        CountingHook standard = new CountingHook();
        gate.addHook(standard);
        // Fire a fire/natural/dispenser-tagged action.
        assertThat(gate.shouldLog(action(ActionType.BURN, "#fire"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.SPREAD, "#natural:grass"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.DISPENSE, "#dispenser"))).isTrue();
        assertThat(standard.seen.get()).isZero();
        assertThat(gate.internalBypassCount()).isEqualTo(3);
    }

    @Test
    void nonMixinSourced_runsStandardHookChain() {
        EventGate gate = new EventGate(defaultActions());
        CountingHook standard = new CountingHook();
        gate.addHook(standard);
        assertThat(gate.shouldLog(action(ActionType.BLOCK_BREAK, "player:notch"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.EXPLOSION, "#tnt"))).isTrue();  // #tnt is NOT a mixin prefix
        assertThat(standard.seen.get()).isEqualTo(2);
        assertThat(gate.internalBypassCount()).isEqualTo(0);
    }

    @Test
    void internalHook_seesMixinSourcedActions() {
        EventGate gate = new EventGate(defaultActions());
        CountingHook standard = new CountingHook();
        CountingHook internal = new CountingHook();
        gate.addHook(standard);
        gate.addInternalHook(internal);
        assertThat(gate.shouldLog(action(ActionType.BURN, "#fire"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.SPREAD, "#natural"))).isTrue();
        assertThat(standard.seen.get()).isZero();
        assertThat(internal.seen.get()).isEqualTo(2);
    }

    @Test
    void internalHook_deniedMixinSourcedAction_isDropped() {
        EventGate gate = new EventGate(defaultActions());
        gate.addInternalHook(a -> EventHook.Decision.DENY);
        assertThat(gate.shouldLog(action(ActionType.BURN, "#fire"))).isFalse();
    }

    @Test
    void standardHook_deniedNonMixinAction_isDropped() {
        EventGate gate = new EventGate(defaultActions());
        gate.addHook(a -> EventHook.Decision.DENY);
        assertThat(gate.shouldLog(action(ActionType.BLOCK_BREAK, "player:notch"))).isFalse();
    }

    @Test
    void internalHook_notConsultedForNonMixinAction() {
        EventGate gate = new EventGate(defaultActions());
        CountingHook internal = new CountingHook();
        gate.addInternalHook(internal);
        assertThat(gate.shouldLog(action(ActionType.BLOCK_BREAK, "player:notch"))).isTrue();
        assertThat(internal.seen.get()).isZero();
    }

    @Test
    void nullSourceTag_treatedAsNonMixin() {
        EventGate gate = new EventGate(defaultActions());
        CountingHook standard = new CountingHook();
        CountingHook internal = new CountingHook();
        gate.addHook(standard);
        gate.addInternalHook(internal);
        assertThat(gate.shouldLog(action(ActionType.BLOCK_BREAK, null))).isTrue();
        assertThat(standard.seen.get()).isEqualTo(1);
        assertThat(internal.seen.get()).isZero();
    }
}
