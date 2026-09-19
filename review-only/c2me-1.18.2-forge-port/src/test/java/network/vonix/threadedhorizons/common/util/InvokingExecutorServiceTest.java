package network.vonix.threadedhorizons.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvokingExecutorServiceTest {

    @Test
    void lifecycleAndInvokeAllRunOnCaller() throws Exception {
        InvokingExecutorService executor = new InvokingExecutorService();
        AtomicInteger count = new AtomicInteger();
        executor.execute(count::incrementAndGet);
        assertEquals(2, executor.invokeAny(List.<Callable<Integer>>of(count::incrementAndGet)).intValue());
        executor.invokeAll(List.<Callable<Integer>>of(count::incrementAndGet));
        executor.shutdown();
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(1, TimeUnit.MILLISECONDS));
        assertTrue(executor.isTerminated());
        assertEquals(3, count.get());
    }
}
