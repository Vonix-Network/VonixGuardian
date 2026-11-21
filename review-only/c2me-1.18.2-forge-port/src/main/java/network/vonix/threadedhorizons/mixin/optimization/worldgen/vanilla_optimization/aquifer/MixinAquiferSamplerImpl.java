package network.vonix.threadedhorizons.mixin.optimization.worldgen.vanilla_optimization.aquifer;

import network.vonix.threadedhorizons.common.optimization.worldgen.random_instances.RandomUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.minecraft.core.BlockPos.PACKED_X_LENGTH;

@Mixin(Aquifer.NoiseBasedAquifer.class)
public class MixinAquiferSamplerImpl {

    @Unique
    private static final int WATER_LEVEL_MAGIC_1 = 64 - BlockPos.X_OFFSET - BlockPos.PACKED_X_LENGTH;
    @Unique
    private static final int WATER_LEVEL_MAGIC_2 = 64 - BlockPos.PACKED_X_LENGTH;
    @Unique
    private static final int WATER_LEVEL_MAGIC_3 = 64 - BlockPos.PACKED_Y_LENGTH;
    @Unique
    private static final int WATER_LEVEL_MAGIC_4 = 64 - BlockPos.PACKED_Y_LENGTH;
    @Unique
    private static final int WATER_LEVEL_MAGIC_5 = 64 - BlockPos.Z_OFFSET - BlockPos.PACKED_Z_LENGTH;
    @Unique
    private static final int WATER_LEVEL_MAGIC_6 = 64 - BlockPos.PACKED_Z_LENGTH;


    @Shadow
    @Final
    private int minGridX;

    @Shadow
    @Final
    private int minGridY;

    @Shadow
    @Final
    private int minGridZ;

    @Shadow
    @Final
    private int gridSizeZ;

    @Shadow @Final private int gridSizeX;

    @Shadow @Final private long[] aquiferLocationCache;

    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;

    @Shadow
    @Final
    private Aquifer.FluidStatus[] aquiferCache;

    @Shadow
    @Final
    private static int[][] SURFACE_SAMPLING_OFFSETS_IN_CHUNKS;

    @Shadow
    @Final
    private NoiseChunk noiseChunk;

    @Shadow
    @Final
    private DensityFunction barrierNoise;

    @Shadow
    @Final
    private DensityFunction fluidLevelFloodednessNoise;

    @Shadow
    @Final
    private DensityFunction fluidLevelSpreadNoise;

    @Shadow
    @Final
    private DensityFunction lavaNoise;

    @Shadow
    @Final
    private static double FLOWING_UPDATE_SIMULARITY;

    @Shadow
    private boolean shouldScheduleFluidUpdate;

    @Shadow
    @Final
    private Aquifer.FluidPicker globalFluidPicker;

    @Unique
    private RandomSource randomInstance;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.randomInstance = RandomUtils.getRandom(this.positionalRandomFactory);
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    @Nullable
    public BlockState computeSubstance(DensityFunction.FunctionContext arg, double d) {
        final int blockX = arg.blockX();
        final int blockY = arg.blockY();
        final int blockZ = arg.blockZ();
        if (d > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        } else {
            Aquifer.FluidStatus fluidLevel = this.globalFluidPicker.computeFluid(blockX, blockY, blockZ);
            if (fluidLevel.at(blockY).is(Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = false;
                return Blocks.LAVA.defaultBlockState();
            } else {
                int l = Math.floorDiv(blockX - 5, 16);
                int m = Math.floorDiv(blockY + 1, 12);
                int n = Math.floorDiv(blockZ - 5, 16);
                int o = Integer.MAX_VALUE;
                int p = Integer.MAX_VALUE;
                int q = Integer.MAX_VALUE;
                long r = 0L;
                long s = 0L;
                long t = 0L;

                for (int u = 0; u <= 1; ++u) {
                    for (int v = -1; v <= 1; ++v) {
                        for (int w = 0; w <= 1; ++w) {
                            int x = l + u;
                            int y = m + v;
                            int z = n + w;
                            int aa = ((y - this.minGridY) * this.gridSizeZ + z - this.minGridZ) * this.gridSizeX + x - this.minGridX;
                            long ab = this.aquiferLocationCache[aa];
                            long ac;
                            if (ab != Long.MAX_VALUE) {
                                ac = ab;
                            } else {
                                // C2ME - reuse random instance
                                RandomUtils.derive(this.positionalRandomFactory, this.randomInstance, x, y, z);
                                final int i1 = randomInstance.nextInt(10);
                                final int i2 = randomInstance.nextInt(9);
                                final int i3 = randomInstance.nextInt(10);
                                ac = BlockPos.asLong(x * 16 + i1, y * 12 + i2, z * 16 + i3);
                                this.aquiferLocationCache[aa] = ac;
                            }

                            int ad = (int) ((ac << WATER_LEVEL_MAGIC_1) >> WATER_LEVEL_MAGIC_2) - blockX; // C2ME - inline
                            int ae = (int) ((ac << WATER_LEVEL_MAGIC_3) >> WATER_LEVEL_MAGIC_4) - blockY; // C2ME - inline
                            int af = (int) ((ac << WATER_LEVEL_MAGIC_5) >> WATER_LEVEL_MAGIC_6) - blockZ; // C2ME - inline
                            int ag = ad * ad + ae * ae + af * af;
                            if (o >= ag) {
                                t = s;
                                s = r;
                                r = ac;
                                q = p;
                                p = o;
                                o = ag;
                            } else if (p >= ag) {
                                t = s;
                                s = ac;
                                q = p;
                                p = ag;
                            } else if (q >= ag) {
                                t = ac;
                                q = ag;
                            }
                        }
                    }
                }

                Aquifer.FluidStatus fluidLevel2 = this.getAquiferStatus(r);
                double e = 1.0 - Math.abs(p - o) / 25.0; // C2ME - inline
                final BlockState fluidLevel2BlockState = fluidLevel2.at(blockY);
                if (e <= 0.0) {
                    this.shouldScheduleFluidUpdate = e >= FLOWING_UPDATE_SIMULARITY;
                    return fluidLevel2BlockState;
                } else {
                    final boolean fluidLevel2BlockStateOfWater = fluidLevel2BlockState.is(Blocks.WATER);
                    if (fluidLevel2BlockStateOfWater && this.globalFluidPicker.computeFluid(blockX, blockY - 1, blockZ).at(blockY - 1).is(Blocks.LAVA)) {
                        this.shouldScheduleFluidUpdate = true;
                        return fluidLevel2BlockState;
                    } else {
                        double mutableDouble = Double.NaN;
                        Aquifer.FluidStatus fluidLevel3 = this.getAquiferStatus(s);
                        double result1;
                        final BlockState fluidLevel3BlockState = fluidLevel3.at(blockY);
                        final boolean fluidLevel2BlockStateOfLava = fluidLevel2BlockState.is(Blocks.LAVA);
                        final boolean fluidLevel3BlockStateOfLava = fluidLevel3BlockState.is(Blocks.LAVA);
                        final boolean fluidLevel3BlockStateOfWater = fluidLevel3BlockState.is(Blocks.WATER);
                        if ((!fluidLevel2BlockStateOfLava || !fluidLevel3BlockStateOfWater) && (!fluidLevel2BlockStateOfWater || !fluidLevel3BlockStateOfLava)) {
                            int j2 = Math.abs(fluidLevel2.fluidLevel - fluidLevel3.fluidLevel);
                            if (j2 == 0) {
                                result1 = 0.0;
                            } else {
                                double d2 = 0.5 * (fluidLevel2.fluidLevel + fluidLevel3.fluidLevel);
                                double e2 = blockY + 0.5 - d2;
                                double f2 = j2 / 2.0;
                                double o2 = f2 - Math.abs(e2);
                                double q2;
                                if (e2 > 0.0) {
                                    double p2 = 0.0 + o2;
                                    q2 = p2 > 0.0 ? p2 / 1.5 : p2 / 2.5;
                                } else {
                                    double p2 = 3.0 + o2;
                                    q2 = p2 > 0.0 ? p2 / 3.0 : p2 / 10.0;
                                }

                                double r2;
                                if (!(q2 < -2.0) && !(q2 > 2.0)) {
                                    double t2 = this.barrierNoise.compute(arg);
                                    mutableDouble = t2;
                                    r2 = t2;
                                } else {
                                    r2 = 0.0;
                                }

                                result1 = 2.0 * (r2 + q2);
                            }
                        } else {
                            result1 = 2.0;
                        }
                        double f = e * result1;
                        if (d + f > 0.0) {
                            this.shouldScheduleFluidUpdate = false;
                            return null;
                        } else {
                            Aquifer.FluidStatus fluidLevel4 = this.getAquiferStatus(t);
                            double g = 1.0 - (double) Math.abs(q - o) / 25.0;
                            final BlockState fluidLevel4BlockState = fluidLevel4.at(blockY);
                            final boolean fluidLevel4BlockStateOfWater = fluidLevel4BlockState.is(Blocks.WATER);
                            final boolean fluidLevel4BlockStateOfLava = fluidLevel4BlockState.is(Blocks.LAVA);
                            if (g > 0.0) {
                                double result;
                                if ((!fluidLevel2BlockStateOfLava || !fluidLevel4BlockStateOfWater) && (!fluidLevel2BlockStateOfWater || !fluidLevel4BlockStateOfLava)) {
                                    int j1 = Math.abs(fluidLevel2.fluidLevel - fluidLevel4.fluidLevel);
                                    if (j1 == 0) {
                                        result = 0.0;
                                    } else {
                                        double d1 = 0.5 * (fluidLevel2.fluidLevel + fluidLevel4.fluidLevel);
                                        double e1 = blockY + 0.5 - d1;
                                        double f1 = j1 / 2.0;
                                        double o1 = f1 - Math.abs(e1);
                                        double q1;
                                        if (e1 > 0.0) {
                                            double p1 = 0.0 + o1;
                                            q1 = p1 > 0.0 ? p1 / 1.5 : p1 / 2.5;
                                        } else {
                                            double p1 = 3.0 + o1;
                                            q1 = p1 > 0.0 ? p1 / 3.0 : p1 / 10.0;
                                        }

                                        double r1;
                                        if (!(q1 < -2.0) && !(q1 > 2.0)) {
                                            if (Double.isNaN(mutableDouble)) {
                                                double t1 = this.barrierNoise.compute(arg);
                                                mutableDouble = t1;
                                                r1 = t1;
                                            } else {
                                                r1 = mutableDouble;
                                            }
                                        } else {
                                            r1 = 0.0;
                                        }

                                        result = 2.0 * (r1 + q1);
                                    }
                                } else {
                                    result = 2.0;
                                }
                                double h = e * g * result;
                                if (d + h > 0.0) {
                                    this.shouldScheduleFluidUpdate = false;
                                    return null;
                                }
                            }

                            double h = 1.0 - (double) Math.abs(q - p) / 25.0;
                            if (h > 0.0) {
                                double result;
                                if ((!fluidLevel3BlockStateOfLava || !fluidLevel4BlockStateOfWater) && (!fluidLevel3BlockStateOfWater || !fluidLevel4BlockStateOfLava)) {
                                    int j1 = Math.abs(fluidLevel3.fluidLevel - fluidLevel4.fluidLevel);
                                    if (j1 == 0) {
                                        result = 0.0;
                                    } else {
                                        double d1 = 0.5 * (fluidLevel3.fluidLevel + fluidLevel4.fluidLevel);
                                        double e1 = blockY + 0.5 - d1;
                                        double f1 = j1 / 2.0;
                                        double o1 = f1 - Math.abs(e1);
                                        double q1;
                                        if (e1 > 0.0) {
                                            double p1 = 0.0 + o1;
                                            q1 = p1 > 0.0 ? p1 / 1.5 : p1 / 2.5;
                                        } else {
                                            double p1 = 3.0 + o1;
                                            q1 = p1 > 0.0 ? p1 / 3.0 : p1 / 10.0;
                                        }

                                        double r1;
                                        if (!(q1 < -2.0) && !(q1 > 2.0)) {
                                            if (Double.isNaN(mutableDouble)) {
                                                double t1 = this.barrierNoise.compute(arg);
                                                mutableDouble = t1;
                                                r1 = t1;
                                            } else {
                                                r1 = mutableDouble;
                                            }
                                        } else {
                                            r1 = 0.0;
                                        }

                                        result = 2.0 * (r1 + q1);
                                    }
                                } else {
                                    result = 2.0;
                                }
                                double ah = e * h * result;
                                if (d + ah > 0.0) {
                                    this.shouldScheduleFluidUpdate = false;
                                    return null;
                                }
                            }

                            this.shouldScheduleFluidUpdate = true;
                            return fluidLevel2BlockState;
                        }
                    }
                }
            }
        }
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private Aquifer.FluidStatus getAquiferStatus(long pos) {
        int i = (int) ((pos << WATER_LEVEL_MAGIC_1) >> WATER_LEVEL_MAGIC_2); // C2ME - inline
        int j = (int) ((pos << WATER_LEVEL_MAGIC_3) >> WATER_LEVEL_MAGIC_4); // C2ME - inline
        int k = (int) ((pos << WATER_LEVEL_MAGIC_5) >> WATER_LEVEL_MAGIC_6); // C2ME - inline
        int l = Math.floorDiv(i, 16); // C2ME - inline
        int m = Math.floorDiv(j, 12); // C2ME - inline
        int n = Math.floorDiv(k, 16); // C2ME - inline
        int o = ((m - this.minGridY) * this.gridSizeZ + n - this.minGridZ) * this.gridSizeX + l - this.minGridX;
        Aquifer.FluidStatus fluidLevel = this.aquiferCache[o];
        if (fluidLevel != null) {
            return fluidLevel;
        } else {
            Aquifer.FluidStatus fluidLevel2 = this.computeFluid(i, j, k);
            this.aquiferCache[o] = fluidLevel2;
            return fluidLevel2;
        }
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private Aquifer.FluidStatus computeFluid(int i, int j, int k) {
        Aquifer.FluidStatus fluidLevel = this.globalFluidPicker.computeFluid(i, j, k);
        int l = Integer.MAX_VALUE;
        int m = j + 12;
        int n = j - 12;
        boolean bl = false;

        for (int[] is : SURFACE_SAMPLING_OFFSETS_IN_CHUNKS) {
            int o = i + (is[0] << 4); // C2ME - inline
            int p = k + (is[1] << 4); // C2ME - inline
            int q = this.noiseChunk.preliminarySurfaceLevel(o, p);
            int r = q + 8;
            boolean bl2 = is[0] == 0 && is[1] == 0;
            if (bl2 && n > r) {
                return fluidLevel;
            }

            boolean bl3 = m > r;
            if (bl2 || bl3) {
                Aquifer.FluidStatus fluidLevel2 = this.globalFluidPicker.computeFluid(o, r, p);
                if (!fluidLevel2.at(r).isAir()) {
                    if (bl2) {
                        bl = true;
                    }

                    if (bl3) {
                        return fluidLevel2;
                    }
                }
            }

            l = Math.min(l, q);
        }

        int s = l + 8 - j;
        double d = bl ? clampedLerpFromProgressInlined(s) : 0.0;
        double e = Mth.clamp(this.fluidLevelFloodednessNoise.compute(new DensityFunction.SinglePointContext(i, j, k)), -1.0, 1.0);
        double f = lerpFromProgressInlined(d, -0.3, 0.8);
        if (e > f) {
            return fluidLevel;
        } else {
            double g = lerpFromProgressInlined(d, -0.8, 0.4);
            if (e <= g) {
                return new Aquifer.FluidStatus(DimensionType.WAY_BELOW_MIN_Y, fluidLevel.fluidType);
            } else {
                int w = Math.floorDiv(i, 16);
                int x = Math.floorDiv(j, 40);
                int y = Math.floorDiv(k, 16);
                int z = x * 40 + 20;
                double h = this.fluidLevelSpreadNoise.compute(new DensityFunction.SinglePointContext(w, x, y)) * 10.0;
                int ab = Mth.quantize(h, 3);
                int ac = z + ab;
                int ad = Math.min(l, ac);
                if (ac <= -10) {
                    int ag = Math.floorDiv(i, 64);
                    int ah = Math.floorDiv(j, 40);
                    int ai = Math.floorDiv(k, 64);
                    double aj = this.lavaNoise.compute(new DensityFunction.SinglePointContext(ag, ah, ai));
                    if (Math.abs(aj) > 0.3) {
                        return new Aquifer.FluidStatus(ad, Blocks.LAVA.defaultBlockState());
                    }
                }

                return new Aquifer.FluidStatus(ad, fluidLevel.fluidType);
            }
        }
    }

    @Unique
    private static double clampedLerpFromProgressInlined(double lerpValue) {
        final double delta = lerpValue / 64.0;
        if (delta < 0.0) {
            return 1.0;
        } else {
            return delta > 1.0 ? 0.0 : 1.0 - delta;
        }
    }

    @Unique
    private static double lerpFromProgressInlined(double lerpValue, double start, double end) {
        return start - (lerpValue - 1.0) * (end - start);
    }

}
