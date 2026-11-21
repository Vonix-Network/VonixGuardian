package network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache.IGlobalBiomeCache;
import network.vonix.threadedhorizons.common.util.PalettedContainerUtil;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedChunkAnvilStorage {

    @Shadow
    protected abstract CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkLoad(ChunkPos pos);

    @Shadow
    @Final
    private ChunkGenerator generator;

    @Redirect(method = "schedule", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> redirectLoadChunk(ChunkMap chunkMap, ChunkPos pos) {
        if (this.generator.getBiomeSource() instanceof IGlobalBiomeCache source)
            return this.scheduleChunkLoad(pos).thenApplyAsync(either -> {
                either.left().ifPresent(chunk -> {
                    for (LevelChunkSection chunkSection : chunk.getSections()) {
                        final SectionPos chunkSectionPos = SectionPos.of(chunk.getPos(), chunkSection.bottomBlockY());
                        final Holder<Biome>[][][] biomes = source.preloadBiomes(chunkSectionPos, chunk.getStatus().isOrAfter(ChunkStatus.FEATURES) ? null : PalettedContainerUtil.toArray(chunkSection.getBiomes(), 4, 4, 4), this.generator.climateSampler());
                        PalettedContainerUtil.writeArray(chunkSection.getBiomes(), biomes);
                    }
                });
                return either;
            }, GlobalExecutors.invokingExecutor);
        else
            return this.scheduleChunkLoad(pos);
    }

}
