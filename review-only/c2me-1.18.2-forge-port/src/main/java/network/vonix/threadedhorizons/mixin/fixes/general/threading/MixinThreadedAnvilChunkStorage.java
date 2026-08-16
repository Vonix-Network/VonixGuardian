package network.vonix.threadedhorizons.mixin.fixes.general.threading;

import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow @Final private ServerLevel level;

    @Redirect(method = "schedule", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;addTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"))
    private <T> void redirectAddLightTicket(ChunkMap.DistanceManager distanceManager, TicketType<T> type, ChunkPos pos, int level, T argument) {
        if (this.level.getServer().getRunningThread() != Thread.currentThread()) {
            this.mainThreadExecutor.execute(() -> distanceManager.addTicket(type, pos, level, argument));
        } else {
            distanceManager.addTicket(type, pos, level, argument);
        }
    }

}
