package network.vonix.guardian.core.event;

import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X6 (P3-5): {@link EventGate#addInternalHook} soft-cap. Registration
 * past the cap does NOT throw (it's a soft cap, not a hard cap) — but the
 * {@link EventGate#internalHooks()} snapshot grows past
 * {@link EventGate#INTERNAL_HOOKS_SOFT_CAP}, and a WARN is emitted at most once.
 */
class EventGateInternalHooksCapTest {

    @Test
    void softcap_constant_is_32() {
        assertThat(EventGate.INTERNAL_HOOKS_SOFT_CAP).isEqualTo(32);
    }

    @Test
    void registering_past_cap_does_not_throw_and_hooks_grow() {
        EventGate gate = new EventGate(GuardianConfig.defaults().actions());
        // Register 40 hooks — 8 past the soft cap.
        for (int i = 0; i < 40; i++) {
            gate.addInternalHook(a -> EventHook.Decision.PASS);
        }
        assertThat(gate.internalHooks()).hasSize(40);
    }

    @Test
    void hooks_below_cap_do_not_touch_warn_state() {
        EventGate gate = new EventGate(GuardianConfig.defaults().actions());
        for (int i = 0; i < EventGate.INTERNAL_HOOKS_SOFT_CAP; i++) {
            gate.addInternalHook(a -> EventHook.Decision.PASS);
        }
        // The gate must still work end-to-end.
        assertThat(gate.internalHooks()).hasSize(EventGate.INTERNAL_HOOKS_SOFT_CAP);
    }
}
