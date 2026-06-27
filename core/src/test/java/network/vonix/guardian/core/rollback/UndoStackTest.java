package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoStackTest {

    private static RollbackResult dummy(int planned) {
        return new RollbackResult(
            UUID.randomUUID(),
            RollbackResult.Mode.ROLLBACK,
            false,
            List.of((long) planned),
            List.of(),
            planned,
            planned
        );
    }

    @Test
    void pushAndPop_lifoOrder() {
        UndoStack stack = new UndoStack();
        UUID actor = UUID.randomUUID();
        RollbackResult a = dummy(1);
        RollbackResult b = dummy(2);
        RollbackResult c = dummy(3);

        stack.push(actor, a);
        stack.push(actor, b);
        stack.push(actor, c);

        assertThat(stack.pop(actor)).hasValue(c);
        assertThat(stack.pop(actor)).hasValue(b);
        assertThat(stack.pop(actor)).hasValue(a);
        assertThat(stack.pop(actor)).isEmpty();
    }

    @Test
    void capEvictsOldestWhenExceeded() {
        UndoStack stack = new UndoStack(3);
        UUID actor = UUID.randomUUID();
        RollbackResult r1 = dummy(1);
        RollbackResult r2 = dummy(2);
        RollbackResult r3 = dummy(3);
        RollbackResult r4 = dummy(4);

        stack.push(actor, r1);
        stack.push(actor, r2);
        stack.push(actor, r3);
        stack.push(actor, r4);

        List<RollbackResult> hist = stack.history(actor);
        assertThat(hist).containsExactly(r4, r3, r2);
        assertThat(stack.size()).isEqualTo(3);
    }

    @Test
    void perActorIsolation() {
        UndoStack stack = new UndoStack();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RollbackResult a = dummy(1);
        RollbackResult b = dummy(2);

        stack.push(alice, a);
        stack.push(bob, b);

        assertThat(stack.peek(alice)).hasValue(a);
        assertThat(stack.peek(bob)).hasValue(b);
        assertThat(stack.size()).isEqualTo(2);
    }

    @Test
    void nullActorMapsToConsoleKey() {
        UndoStack stack = new UndoStack();
        RollbackResult r = dummy(1);
        stack.push(null, r);
        assertThat(stack.peek(null)).hasValue(r);
        assertThat(stack.peek(UndoStack.CONSOLE_KEY)).hasValue(r);
    }

    @Test
    void peekDoesNotRemove() {
        UndoStack stack = new UndoStack();
        UUID actor = UUID.randomUUID();
        RollbackResult r = dummy(1);
        stack.push(actor, r);
        assertThat(stack.peek(actor)).hasValue(r);
        assertThat(stack.peek(actor)).hasValue(r);
        assertThat(stack.size()).isEqualTo(1);
    }

    @Test
    void emptyActorReturnsEmpty() {
        UndoStack stack = new UndoStack();
        assertThat(stack.pop(UUID.randomUUID())).isEqualTo(Optional.empty());
        assertThat(stack.peek(UUID.randomUUID())).isEqualTo(Optional.empty());
        assertThat(stack.history(UUID.randomUUID())).isEmpty();
    }

    @Test
    void clearAndClearAll() {
        UndoStack stack = new UndoStack();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stack.push(a, dummy(1));
        stack.push(b, dummy(2));
        stack.clear(a);
        assertThat(stack.peek(a)).isEmpty();
        assertThat(stack.peek(b)).isPresent();
        stack.clearAll();
        assertThat(stack.size()).isZero();
    }

    @Test
    void invalidCapRejected() {
        assertThatThrownBy(() -> new UndoStack(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullResultRejected() {
        UndoStack stack = new UndoStack();
        assertThatThrownBy(() -> stack.push(UUID.randomUUID(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
