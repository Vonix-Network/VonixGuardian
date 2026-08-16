package network.vonix.threadedhorizons;

import com.ibm.asyncutil.util.Combinators;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Mod(ThreadedHorizonsMod.MOD_ID)
public class ThreadedHorizonsMod {
    public static final String MOD_ID = "threadedhorizons";
    public static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons");

    public ThreadedHorizonsMod() {
        onInitialize();
        if (LoaderHooks.isClient()) {
            try {
                Class.forName("network.vonix.threadedhorizons.client.ThreadedHorizonsClient")
                        .getMethod("init")
                        .invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize client hooks", e);
            }
        }
    }

    private void onInitialize() {
        if (Boolean.getBoolean("network.vonix.threadedhorizons.mixin.doAudit")) {
            MixinEnvironment.getCurrentEnvironment().audit();
        }
        if (Boolean.getBoolean("network.vonix.threadedhorizons.runCompressionBenchmark")) {
            LOGGER.info("Benchmarking chunk stream speed");
            LOGGER.info("Warming up");
            for (int i = 0; i < 3; i++) {
                runBenchmark("VERSION_GZIP", RegionFileVersion.VERSION_GZIP, true);
                runBenchmark("VERSION_DEFLATE", RegionFileVersion.VERSION_DEFLATE, true);
                runBenchmark("VERSION_NONE", RegionFileVersion.VERSION_NONE, true);
            }
            runBenchmark("VERSION_GZIP", RegionFileVersion.VERSION_GZIP, false);
            runBenchmark("VERSION_DEFLATE", RegionFileVersion.VERSION_DEFLATE, false);
            runBenchmark("VERSION_NONE", RegionFileVersion.VERSION_NONE, false);
        }
        if (ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion != -1 && !RegionFileVersion.isValidVersion(ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion)) {
            LOGGER.error("Unknown chunk stream version in config: {}", ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion);
            throw new IllegalArgumentException(String.format("Unknown chunk stream version in config: %s", ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion));
        }
        consistencyTest();
    }

    private void runBenchmark(String name, RegionFileVersion version, boolean suppressLog) {
        try {
            final DecimalFormat decimalFormat = new DecimalFormat("0.###");
            if (!suppressLog) LOGGER.info("Generating 128MB random data");
            final byte[] bytes = new byte[128 * 1024 * 1024];
            new Random().nextBytes(bytes);
            if (!suppressLog) LOGGER.info("Starting benchmark for {}", name);
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            {
                final OutputStream wrappedOutputStream = version.wrap(outputStream);
                long startTime = System.nanoTime();
                wrappedOutputStream.write(bytes);
                wrappedOutputStream.close();
                long endTime = System.nanoTime();
                if (!suppressLog) LOGGER.info("{} write speed: {} MB/s ({} MB/s compressed)", name, decimalFormat.format((bytes.length / 1024.0 / 1024.0) / ((endTime - startTime) / 1_000_000_000.0)), decimalFormat.format((outputStream.size() / 1024.0 / 1024.0) / ((endTime - startTime) / 1_000_000_000.0)));
                if (!suppressLog) LOGGER.info("{} compression ratio: {} %", name, decimalFormat.format(outputStream.size() / (double) bytes.length * 100.0));
            }
            {
                final ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
                final InputStream wrappedInputStream = version.wrap(inputStream);
                long startTime = System.nanoTime();
                final byte[] readAllBytes = wrappedInputStream.readAllBytes();
                wrappedInputStream.close();
                long endTime = System.nanoTime();
                if (!suppressLog) LOGGER.info("{} read speed: {} MB/s ({} MB/s compressed)", name, decimalFormat.format((readAllBytes.length / 1024.0 / 1024.0) / ((endTime - startTime) / 1_000_000_000.0)), decimalFormat.format((outputStream.size() / 1024.0 / 1024.0) / ((endTime - startTime) / 1_000_000_000.0)));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void consistencyTest() {
        int taskSize = 512;
        AtomicIntegerArray array = new AtomicIntegerArray(taskSize);
        final List<CompletableFuture<Integer>> futures = IntStream.range(0, taskSize)
                .mapToObj(value -> CompletableFuture.supplyAsync(() -> {
                    final WorldgenRandom chunkRandom = new WorldgenRandom(new SingleThreadedRandomSource(System.nanoTime()));
                    chunkRandom.consumeCount(4096);
                    final int i = chunkRandom.nextInt();
                    array.set(value, i);
                    return i;
                }))
                .toList();
        final List<Integer> join = Combinators.collect(futures, Collectors.toList()).toCompletableFuture().join();
        for (int i = 0; i < taskSize; i++) {
            if (array.get(i) != join.get(i))
                throw new IllegalArgumentException("Mismatch at index " + i);
        }
    }
}
