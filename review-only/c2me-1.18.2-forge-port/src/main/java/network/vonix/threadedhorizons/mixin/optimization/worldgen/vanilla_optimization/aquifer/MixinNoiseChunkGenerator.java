package network.vonix.threadedhorizons.mixin.optimization.worldgen.vanilla_optimization.aquifer;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseChunkGenerator {

    @Shadow @Final protected Holder<NoiseGeneratorSettings> settings;

    @Mutable
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;

    @Inject(method = "<init>(Lnet/minecraft/core/Registry;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/BiomeSource;JLnet/minecraft/core/Holder;)V", at = @At("RETURN"))
    private void modifyFluidPicker(CallbackInfo ci) {
        NoiseGeneratorSettings chunkGeneratorSettings = this.settings.value();
        Aquifer.FluidStatus fluidLevel = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int i = chunkGeneratorSettings.seaLevel();
        Aquifer.FluidStatus fluidLevel2 = new Aquifer.FluidStatus(i, chunkGeneratorSettings.defaultFluid());
        final int min = Math.min(-54, i);
        this.globalFluidPicker = (j, k, lx) -> k < min ? fluidLevel : fluidLevel2;
    }

}
