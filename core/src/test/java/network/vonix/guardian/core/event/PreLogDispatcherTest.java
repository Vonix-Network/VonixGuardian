package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreLogDispatcherTest {

    private static Action anyAction() {
        return new Action(-1L, 1_700_000_000_000L, ActionType.BLOCK_BREAK,
                UUID.randomUUID(), "Notch",
                "minecraft:overworld", 1, 64, 2,
                "minecraft:stone", null, 1, false, null);
    }

    @AfterEach
    void reset() {
        PreLogDispatcher.setNative(PreLogDispatcher.NOOP);
    }

    @Test
    void default_noop_returns_event_unchanged() {
        PreLogEvent in = new PreLogEvent(anyAction());
        PreLogEvent out = PreLogDispatcher.current().fireAndReturn(in);
        assertThat(out).isSameAs(in);
        assertThat(out.isCancelled()).isFalse();
        assertThat(out.cancelReason()).isNull();
    }

    @Test
    void custom_impl_can_cancel_event() {
        PreLogDispatcher.setNative(evt -> {
            evt.setCancelled(true, "unit-test-veto");
            return evt;
        });
        PreLogEvent evt = new PreLogEvent(anyAction());
        PreLogEvent out = PreLogDispatcher.current().fireAndReturn(evt);
        assertThat(out.isCancelled()).isTrue();
        assertThat(out.cancelReason()).isEqualTo("unit-test-veto");
    }

    @Test
    void setNative_null_throws() {
        assertThatThrownBy(() -> PreLogDispatcher.setNative(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void setNative_swap_is_visible_to_current() {
        PreLogDispatcher a = evt -> evt;
        PreLogDispatcher.setNative(a);
        assertThat(PreLogDispatcher.current()).isSameAs(a);
        PreLogDispatcher.setNative(PreLogDispatcher.NOOP);
        assertThat(PreLogDispatcher.current()).isSameAs(PreLogDispatcher.NOOP);
    }
}
