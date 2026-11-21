package network.vonix.threadedhorizons.mixin.threading.worldgen;

import com.google.common.base.Preconditions;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DistanceManager.FixedPlayerDistanceChunkTracker.class)
public abstract class MixinChunkTicketManagerDistanceFromNearestPlayerTracker extends ChunkTracker {

    protected MixinChunkTicketManagerDistanceFromNearestPlayerTracker(int i, int j, int k) {
        super(i, j, k);
    }

    @Redirect(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;runUpdates(I)I"))
    private int redirectUpdate(DistanceManager.FixedPlayerDistanceChunkTracker instance, int i) {
        //noinspection ConstantConditions
        Preconditions.checkArgument(instance == (Object) this);
        final int updates = this.runUpdates(Integer.MAX_VALUE);
        if (!((Object) this instanceof DistanceManager.PlayerTicketTracker)) return updates;
        if (Integer.MAX_VALUE - updates != 0) {
            SchedulerThread.INSTANCE.notifyPriorityChange();
        }
        return updates;
    }

}
