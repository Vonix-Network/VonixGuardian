package network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.noise;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(MaterialRuleList.class)
public class MixinChainedBlockSource {

    @Unique
    private NoiseChunk.BlockStateFiller[] samplersArray;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(List<NoiseChunk.BlockStateFiller> samplers, CallbackInfo ci) {
        this.samplersArray = samplers.toArray(NoiseChunk.BlockStateFiller[]::new);
    }

    /**
     * @author ishland
     * @reason reduce allocs using array
     */
    @Overwrite
    public @Nullable BlockState calculate(DensityFunction.FunctionContext arg) {
        for (NoiseChunk.BlockStateFiller blockStateSampler : this.samplersArray) {
            BlockState blockState = blockStateSampler.calculate(arg);
            if (blockState != null) {
                return blockState;
            }
        }

        return null;
    }

}
