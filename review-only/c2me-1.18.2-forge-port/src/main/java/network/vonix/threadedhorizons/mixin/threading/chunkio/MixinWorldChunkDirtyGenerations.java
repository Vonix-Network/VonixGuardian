package network.vonix.threadedhorizons.mixin.threading.chunkio;

import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
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
public abstract class MixinWorldChunkDirtyGenerations extends ChunkAccess {

    public MixinWorldChunkDirtyGenerations(ChunkPos pos, UpgradeData upgradeData, LevelHeightAccessor heightLimitView, Registry<Biome> biome, long inhabitedTime, @Nullable LevelChunkSection[] sectionArrayInitializer, @Nullable BlendingData blendingData) {
        super(pos, upgradeData, heightLimitView, biome, inhabitedTime, sectionArrayInitializer, blendingData);
    }

    /**
     * Mixin 0.8.5 rejects {@code @Inject} {@code @At(FIELD)} into constructors.
     * The proto-to-full constructor writes {@code unsaved} then returns.
     */
    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("RETURN")
    )
    private void onProtoToFullAdvanceGeneration(CallbackInfo ci) {
        if (this.unsaved) {
            DirtyChunkGenerations.markMutated((ChunkAccess) (Object) this);
        }
    }

    /**
     * LevelChunk does not override {@code setUnsaved}; {@code setBlockState} writes
     * {@code unsaved} directly (official owner {@code LevelChunk.unsaved} / SRG {@code f_187603_}).
     * Advance the generation before the field write so a concurrent clear cannot
     * observe the old generation after the chunk is already dirty.
     */
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/chunk/LevelChunk;unsaved:Z", opcode = Opcodes.PUTFIELD)
    )
    private void onSetBlockStateAdvanceGeneration(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        DirtyChunkGenerations.markMutated((ChunkAccess) (Object) this);
    }
}
