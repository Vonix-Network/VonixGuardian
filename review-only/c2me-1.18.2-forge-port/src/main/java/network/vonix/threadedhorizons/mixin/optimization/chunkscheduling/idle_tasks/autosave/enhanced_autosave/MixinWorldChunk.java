package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave;

import network.vonix.threadedhorizons.common.optimization.chunkscheduling.idle_tasks.IThreadedAnvilChunkStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class MixinWorldChunk extends ChunkAccess {

    public MixinWorldChunk(ChunkPos pos, UpgradeData upgradeData, LevelHeightAccessor heightLimitView, Registry<Biome> biome, long inhabitedTime, @Nullable LevelChunkSection[] sectionArrayInitializer, @Nullable BlendingData blendingData) {
        super(pos, upgradeData, heightLimitView, biome, inhabitedTime, sectionArrayInitializer, blendingData);
    }

    /**
     * Mixin 0.8.5 rejects {@code @Inject} {@code @At(FIELD)} into constructors.
     * The proto-to-full constructor writes {@code unsaved} then returns; enqueue at RETURN.
     */
    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("RETURN")
    )
    private void onProtoToFullConstructed(CallbackInfo ci) {
        enqueueDirtyIfUnsaved();
    }

    /**
     * LevelChunk does not override {@code setUnsaved}; {@code setBlockState} writes
     * {@code unsaved} directly (official owner {@code LevelChunk.unsaved} / SRG {@code f_187603_}).
     */
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/chunk/LevelChunk;unsaved:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER)
    )
    private void onSetBlockStateUnsaved(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        enqueueDirtyIfUnsaved();
    }

    private void enqueueDirtyIfUnsaved() {
        //noinspection ConstantConditions
        if (this.unsaved && (Object) this instanceof LevelChunk worldChunk) {
            if (worldChunk.getLevel() instanceof ServerLevel serverWorld) {
                ((IThreadedAnvilChunkStorage) serverWorld.getChunkSource().chunkMap).enqueueDirtyChunkPosForAutoSave(this.getPos());
            }
        }
    }

}
