package network.vonix.threadedhorizons.mixin.util.log4j2shutdownhookisnomore;

import net.minecraft.server.dedicated.DedicatedServer;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DedicatedServer.class)
public class MixinMinecraftDedicatedServer {

    @Inject(method = "onServerExit", at = @At("RETURN"))
    private void onPostShutdown(CallbackInfo ci) {
        network.vonix.threadedhorizons.common.GlobalExecutors.shutdown(10, java.util.concurrent.TimeUnit.SECONDS);
        network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread.INSTANCE.shutdown();
        LogManager.shutdown();
    }

}
