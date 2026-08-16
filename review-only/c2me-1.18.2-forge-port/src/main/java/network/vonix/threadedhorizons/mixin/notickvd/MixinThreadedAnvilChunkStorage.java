package network.vonix.threadedhorizons.mixin.notickvd;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.notickvd.IChunkHolder;
import network.vonix.threadedhorizons.mixin.access.IServerChunkManager;
import com.mojang.datafixers.util.Either;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage {

    @Shadow protected abstract void playerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> mutableObject, LevelChunk chunk);

    @Shadow public abstract List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge);

    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;

    @ModifyArg(method = "setViewDistance", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I"), index = 2)
    private int modifyMaxVD(int max) {
        return 251;
    }

    @Redirect(method = "updateChunkTracking", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk redirectSendWatchPacketsGetWorldChunk(ChunkHolder chunkHolder) {
        return ((IChunkHolder) chunkHolder).getAccessibleChunk();
    }

    @Inject(method = "prepareAccessibleChunk", at = @At("RETURN"))
    private void onMakeChunkAccessible(ChunkHolder chunkHolder, CallbackInfoReturnable<CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>>> cir) {
        cir.getReturnValue().thenAccept(either -> either.left().ifPresent(worldChunk -> {
            MutableObject<ClientboundLevelChunkWithLightPacket> mutableObject = new MutableObject<>();
            this.getPlayers(worldChunk.getPos(), false).forEach((serverPlayerEntity) -> {
                if (ThreadedHorizonsConfig.noTickViewDistanceConfig.compatibilityMode) {
                    this.mainThreadExecutor.tell(() -> this.playerLoadedChunk(serverPlayerEntity, mutableObject, worldChunk));
                } else {
                    this.playerLoadedChunk(serverPlayerEntity, mutableObject, worldChunk);
                }
            });
        }));
    }

}
