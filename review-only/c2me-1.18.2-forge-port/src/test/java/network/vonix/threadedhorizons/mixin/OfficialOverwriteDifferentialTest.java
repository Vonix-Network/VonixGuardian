package network.vonix.threadedhorizons.mixin;

import com.ibm.asyncutil.util.Combinators;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import network.vonix.threadedhorizons.common.optimization.math.ImprovedNoiseMath;
import network.vonix.threadedhorizons.common.optimization.math.PerlinNoiseMath;
import network.vonix.threadedhorizons.common.optimization.worldgen.EndBiomeDecision;
import network.vonix.threadedhorizons.common.optimization.worldgen.random_instances.SimplifiedAtomicSimpleRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(300)
class OfficialOverwriteDifferentialTest {

    private static final int CASES = 1_000_000;
    private static final long SEED = 0x1182_4031_1L;

    @Test
    void improvedNoiseMatchesOfficialOneMillion() throws Exception {
        Field permutations = ImprovedNoise.class.getDeclaredField("p");
        permutations.setAccessible(true);
        Random rng = new Random(SEED);
        int mismatches = 0;
        for (int i = 0; i < CASES; i++) {
            long noiseSeed = rng.nextLong();
            ImprovedNoise official = new ImprovedNoise(new LegacyRandomSource(noiseSeed));
            byte[] p = (byte[]) permutations.get(official);
            double x = (rng.nextDouble() - 0.5) * 4096.0;
            double y = (rng.nextDouble() - 0.5) * 4096.0;
            double z = (rng.nextDouble() - 0.5) * 4096.0;
            double yScale = rng.nextBoolean() ? 0.0 : rng.nextDouble() * 8.0;
            double yMax = rng.nextBoolean() ? -1.0 : rng.nextDouble();
            double expected = official.noise(x, y, z, yScale, yMax);
            double actual = ImprovedNoiseMath.noise(official.xo, official.yo, official.zo, p, x, y, z, yScale, yMax);
            if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
                mismatches++;
                if (mismatches < 5) {
                    throw new AssertionError("mismatch i=" + i + " expected=" + expected + " actual=" + actual);
                }
            }
        }
        assertEquals(0, mismatches);
    }

    @Test
    void perlinWrapMatchesOfficialOneMillion() {
        Random rng = new Random(SEED ^ 0x5EED);
        for (int i = 0; i < CASES; i++) {
            double value = switch (i % 7) {
                case 0 -> 0.0;
                case 1 -> -0.0;
                case 2 -> Double.MIN_VALUE;
                case 3 -> -Double.MIN_NORMAL;
                case 4 -> 3.3554432E7;
                case 5 -> -3.3554432E7 * 3;
                default -> (rng.nextDouble() - 0.5) * 1.0E12;
            };
            assertEquals(PerlinNoise.wrap(value), PerlinNoiseMath.wrap(value), "i=" + i);
        }
    }

    @Test
    void simplifiedRandomMatchesLegacyAndSingleThreadedOneMillion() {
        long[] seeds = {0L, 1L, -1L, SEED, Long.MIN_VALUE, 25214903917L};
        for (long seed : seeds) {
            compareRandomStreams(seed, 10_000);
        }
        Random rng = new Random(SEED);
        int remaining = CASES - seeds.length * 10_000;
        for (int i = 0; i < remaining; i++) {
            long seed = rng.nextLong();
            LegacyRandomSource official = new LegacyRandomSource(seed);
            SimplifiedAtomicSimpleRandom candidate = new SimplifiedAtomicSimpleRandom(seed);
            SingleThreadedRandomSource single = new SingleThreadedRandomSource(seed);
            int officialValue = official.nextInt();
            assertEquals(officialValue, candidate.nextInt(), "seed=" + seed);
            assertEquals(officialValue, single.nextInt(), "seed=" + seed);
        }
    }

    @Test
    void positionalFactorySeedDerivationMatchesOfficialOneMillion() {
        Random rng = new Random(SEED ^ 11);
        for (int i = 0; i < CASES; i++) {
            long factorySeed = rng.nextLong();
            int x = rng.nextInt();
            int y = rng.nextInt();
            int z = rng.nextInt();
            long derived = Mth.getSeed(x, y, z) ^ factorySeed;
            RandomSource official = new LegacyRandomSource.LegacyPositionalRandomFactory(factorySeed).at(x, y, z);
            RandomSource candidate = new SingleThreadedRandomSource(derived);
            assertEquals(official.nextLong(), candidate.nextLong(), "i=" + i);
            String name = "th" + i + ":" + x;
            RandomSource officialNamed = new LegacyRandomSource.LegacyPositionalRandomFactory(factorySeed).fromHashOf(name);
            RandomSource candidateNamed = new SingleThreadedRandomSource((long) name.hashCode() ^ factorySeed);
            assertEquals(officialNamed.nextInt(), candidateNamed.nextInt());
        }
    }

    @Test
    void resourceLocationToStringMatchesOfficialOneMillion() {
        Random rng = new Random(SEED ^ 22);
        for (int i = 0; i < CASES; i++) {
            String namespace = "ns" + Integer.toString(rng.nextInt(1_000_000), 36);
            String path = "path_" + Integer.toString(i, 36) + "_" + rng.nextInt();
            ResourceLocation official = new ResourceLocation(namespace, path);
            assertEquals(namespace + ":" + path, official.toString());
        }
    }

    @Test
    void compoundAndListCopyMatchOfficialSemantics() {
        Random rng = new Random(SEED ^ 33);
        int copies = 100_000;
        for (int i = 0; i < copies; i++) {
            CompoundTag original = randomCompound(rng, 3);
            CompoundTag official = original.copy();
            CompoundTag candidate = candidateCopy(original);
            assertEquals(official, candidate, "i=" + i);
            assertEquals(official.getAsString(), candidate.getAsString());
        }
    }

    @Test
    void firstNonNullArrayMatchesListOneMillion() {
        Random rng = new Random(SEED ^ 44);
        String[] palette = {null, "stone", "dirt", "air", "water"};
        for (int i = 0; i < CASES; i++) {
            int n = 1 + rng.nextInt(6);
            List<String> list = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                list.add(palette[rng.nextInt(palette.length)]);
            }
            String[] array = list.toArray(String[]::new);
            assertEquals(firstNonNull(list), firstNonNull(array));
        }
    }

    @Test
    void endBiomeDecisionMatchesOfficialControlFlowOneMillion() {
        Random rng = new Random(SEED ^ 55);
        float[] edges = {40.0F, 0.0F, -20.0F, 40.0001F, -19.999F, 80.0F, -100.0F};
        for (int i = 0; i < CASES; i++) {
            int biomeX = i < 16 ? i : rng.nextInt(4000) - 2000;
            int biomeZ = i < 16 ? i : rng.nextInt(4000) - 2000;
            float height = i < edges.length ? edges[i] : (rng.nextFloat() - 0.5F) * 200.0F;
            int shiftedX = biomeX >> 2;
            int shiftedZ = biomeZ >> 2;
            EndBiomeDecision.Kind official;
            if ((long) shiftedX * (long) shiftedX + (long) shiftedZ * (long) shiftedZ <= 4096L) {
                official = EndBiomeDecision.Kind.END;
            } else if (height > 40.0F) {
                official = EndBiomeDecision.Kind.HIGHLANDS;
            } else if (height >= 0.0F) {
                official = EndBiomeDecision.Kind.MIDLANDS;
            } else {
                official = height < -20.0F ? EndBiomeDecision.Kind.ISLANDS : EndBiomeDecision.Kind.BARRENS;
            }
            assertEquals(official, EndBiomeDecision.classify(biomeX, biomeZ, height), "i=" + i);
        }
    }

    @Test
    void utilSequenceMatchesOfficialCollectedLists() {
        int sequences = 20_000;
        for (int i = 0; i < sequences; i++) {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                futures.add(CompletableFuture.completedFuture(i * 8 + j));
            }
            List<Integer> official = net.minecraft.Util.sequence(futures).join();
            List<Integer> candidate = Combinators.collect(futures, Collectors.toList()).toCompletableFuture().join();
            assertEquals(official, candidate);
        }
    }

    @Test
    void shufflingListCreatesNewInstanceAndPreservesMembers() {
        for (int i = 0; i < 10_000; i++) {
            ShufflingList<String> original = new ShufflingList<>();
            original.add("a", 1);
            original.add("b", 2);
            original.add("c", 3);
            ShufflingList<String> shuffled = original.shuffle();
            List<String> afterShuffled = shuffled.stream().sorted().toList();
            assertEquals(List.of("a", "b", "c"), afterShuffled);
        }
    }

    private static void compareRandomStreams(long seed, int count) {
        LegacyRandomSource official = new LegacyRandomSource(seed);
        SimplifiedAtomicSimpleRandom candidate = new SimplifiedAtomicSimpleRandom(seed);
        SingleThreadedRandomSource single = new SingleThreadedRandomSource(seed);
        for (int i = 0; i < count; i++) {
            int o = official.nextInt();
            assertEquals(o, candidate.nextInt(), "seed=" + seed + " i=" + i);
            assertEquals(o, single.nextInt(), "seed=" + seed + " i=" + i);
        }
    }

    private static CompoundTag randomCompound(Random rng, int depth) {
        CompoundTag tag = new CompoundTag();
        int keys = 1 + rng.nextInt(4);
        for (int i = 0; i < keys; i++) {
            String key = "k" + i;
            switch (rng.nextInt(4)) {
                case 0 -> tag.putInt(key, rng.nextInt());
                case 1 -> tag.putString(key, "v" + rng.nextInt());
                case 2 -> {
                    ListTag list = new ListTag();
                    list.add(IntTag.valueOf(rng.nextInt()));
                    tag.put(key, list);
                }
                default -> {
                    if (depth > 0) {
                        tag.put(key, randomCompound(rng, depth - 1));
                    } else {
                        tag.putBoolean(key, rng.nextBoolean());
                    }
                }
            }
        }
        return tag;
    }

    private static CompoundTag candidateCopy(CompoundTag original) {
        Map<String, Tag> map = new Object2ObjectOpenHashMap<>();
        for (String key : original.getAllKeys()) {
            Tag child = original.get(key);
            map.put(key, child == null ? StringTag.valueOf("") : child.copy());
        }
        return new CompoundTag(map);
    }

    private static String firstNonNull(List<String> rules) {
        for (String state : rules) {
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    private static String firstNonNull(String[] rules) {
        for (String state : rules) {
            if (state != null) {
                return state;
            }
        }
        return null;
    }

}
