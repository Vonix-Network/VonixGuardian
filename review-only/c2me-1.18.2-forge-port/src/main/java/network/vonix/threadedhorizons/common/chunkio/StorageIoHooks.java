package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Test-only injection points. Production leaves every hook unset.
 */
public final class StorageIoHooks {

    public volatile Consumer<CompoundTag> beforeSerialize;
    public volatile Consumer<Long> afterSerialize;
    public final ConcurrentHashMap<Long, CountDownLatch> serializeLatches = new ConcurrentHashMap<>();
    public volatile Runnable beforeRead;
    public volatile Runnable beforeWrite;
    public volatile Runnable beforeClear;
    public volatile Runnable beforeFlush;
    public volatile Runnable beforeClose;
    public volatile RuntimeException readFault;
    public volatile RuntimeException serializeFault;
    public volatile RuntimeException writeFault;
    public volatile RuntimeException clearFault;
    public volatile RuntimeException flushFault;
    public volatile RuntimeException closeFault;
    public final AtomicInteger serializeStarts = new AtomicInteger();
    public final AtomicInteger durableWrites = new AtomicInteger();
    public final AtomicInteger durableClears = new AtomicInteger();

    public void awaitSerialize(long generation) throws InterruptedException {
        CountDownLatch latch = this.serializeLatches.get(generation);
        if (latch != null) {
            latch.await();
        }
    }

    public void fire(Runnable hook) {
        if (hook != null) {
            hook.run();
        }
    }

    public void throwIfPresent(RuntimeException fault) {
        if (fault != null) {
            throw fault;
        }
    }

    public void throwWriteFault() {
        if (this.writeFault != null) {
            throw this.writeFault;
        }
    }

    public void throwClearFault() {
        if (this.clearFault != null) {
            throw this.clearFault;
        }
    }
}
