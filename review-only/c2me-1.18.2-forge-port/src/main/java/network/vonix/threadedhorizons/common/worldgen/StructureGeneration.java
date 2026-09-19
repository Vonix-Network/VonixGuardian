package network.vonix.threadedhorizons.common.worldgen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Synchronizes first-time structure/stronghold generation without latching the
 * generated flag if a companion mixin throws during warmup.
 */
public final class StructureGeneration {
    private StructureGeneration() {
    }

    public static void ensureGenerated(
            Object lock,
            BooleanSupplier alreadyGenerated,
            Consumer<Boolean> setGenerated,
            Runnable generate
    ) {
        if (alreadyGenerated.getAsBoolean()) {
            return;
        }
        synchronized (lock) {
            if (alreadyGenerated.getAsBoolean()) {
                return;
            }
            generate.run();
            setGenerated.accept(true);
        }
    }
}
