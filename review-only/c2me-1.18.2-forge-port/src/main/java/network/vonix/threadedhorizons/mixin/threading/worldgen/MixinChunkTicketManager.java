package network.vonix.threadedhorizons.mixin.threading.worldgen;

import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DistanceManager.class)
public class MixinChunkTicketManager {

    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void onTick(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) SchedulerThread.INSTANCE.notifyPriorityChange();
    }

}
