package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave;

import network.vonix.threadedhorizons.common.optimization.chunkscheduling.idle_tasks.IThreadedAnvilChunkStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class MixinChunk {

    @Shadow protected volatile boolean unsaved;

    @Shadow public abstract ChunkPos getPos();

    @Inject(method = "setUnsaved", at = @At("RETURN"))
    private void onSetShouldSave(boolean unsaved, CallbackInfo ci) {
        //noinspection ConstantConditions
        if (this.unsaved && (Object) this instanceof LevelChunk worldChunk) {
            if (worldChunk.getLevel() instanceof ServerLevel serverWorld) {
                ((IThreadedAnvilChunkStorage) serverWorld.getChunkSource().chunkMap).enqueueDirtyChunkPosForAutoSave(this.getPos());
            }
        }
    }

}
