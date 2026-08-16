package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkDirtyGenerations {

    @Inject(method = "setUnsaved", at = @At("HEAD"))
    private void onSetUnsavedAdvanceGeneration(boolean unsaved, CallbackInfo ci) {
        if (unsaved) {
            DirtyChunkGenerations.markMutated((ChunkAccess) (Object) this);
        }
    }
}
