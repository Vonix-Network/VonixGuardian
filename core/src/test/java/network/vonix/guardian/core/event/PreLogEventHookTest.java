package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreLogEventHookTest {

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
    void returns_PASS_when_no_dispatcher_attached() {
        PreLogEventHook hook = new PreLogEventHook();
        assertThat(hook.test(anyAction())).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void returns_DENY_when_dispatcher_cancels() {
        PreLogDispatcher.setNative(evt -> {
            evt.setCancelled(true, "policy");
            return evt;
        });
        PreLogEventHook hook = new PreLogEventHook();
        assertThat(hook.test(anyAction())).isEqualTo(EventHook.Decision.DENY);
    }

    @Test
    void returns_PASS_when_dispatcher_leaves_event_alone() {
        PreLogDispatcher.setNative(evt -> evt);
        PreLogEventHook hook = new PreLogEventHook();
        assertThat(hook.test(anyAction())).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void tolerates_null_return_from_ill_behaved_impl() {
        PreLogDispatcher.setNative(evt -> null);
        PreLogEventHook hook = new PreLogEventHook();
        assertThat(hook.test(anyAction())).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void passes_the_correct_action_to_dispatcher() {
        Action a = anyAction();
        Action[] captured = new Action[1];
        PreLogDispatcher.setNative(evt -> {
            captured[0] = evt.action();
            return evt;
        });
        new PreLogEventHook().test(a);
        assertThat(captured[0]).isSameAs(a);
    }
}
