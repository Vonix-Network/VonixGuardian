package network.vonix.threadedhorizons.mixin.notickvd;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(Level.class)
public class MixinWorld {

    @Shadow @Final public boolean isClientSide;

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void redirectTickingStatus(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        if (this.isClientSide || !cir.getReturnValueZ()) {
            return;
        }
        LevelChunk worldChunk = ((Level) (Object) this).getChunkAt(pos);
        if (worldChunk != null && worldChunk.getFullStatus() == ChunkHolder.FullChunkStatus.BORDER) {
            ((ServerLevel) (Object) this).getChunkSource().blockChanged(pos);
        }
    }

}
