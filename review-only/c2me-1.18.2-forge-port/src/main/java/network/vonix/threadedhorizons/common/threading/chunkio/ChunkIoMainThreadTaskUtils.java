package network.vonix.threadedhorizons.common.threading.chunkio;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;

public class ChunkIoMainThreadTaskUtils {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();

    public static Transaction open() {
        Transaction transaction = new Transaction();
        CURRENT.set(transaction);
        return transaction;
    }

    public static void push() {
        open();
    }

    public static void pop() {
        CURRENT.remove();
    }

    public static void executeMain(Runnable command) {
        Transaction transaction = CURRENT.get();
        if (transaction == null) {
            command.run();
        } else {
            transaction.tasks.addLast(command);
        }
    }

    public static Transaction current() {
        return CURRENT.get();
    }

    public static void drainQueue() {
        Transaction transaction = CURRENT.get();
        if (transaction == null) {
            return;
        }
        transaction.drainOrThrow();
    }

    public static final class Transaction {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        public void drain() {
            Runnable command;
            while ((command = this.tasks.pollFirst()) != null) {
                command.run();
            }
        }

        public void drainOrThrow() {
            Runnable command;
            while ((command = this.tasks.pollFirst()) != null) {
                try {
                    command.run();
                } catch (Throwable throwable) {
                    LOGGER.error("POI consistency task failed", throwable);
                    throw new ChunkLoadException(null, ChunkLoadException.Kind.POI, "POI consistency failed", throwable);
                }
            }
        }
    }
}
