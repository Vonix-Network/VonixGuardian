package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.task_scheduling;

import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockableEventLoop.class)
public abstract class MixinThreadExecutor<R extends Runnable> {

    @Shadow @Final private static Logger LOGGER;

    @Shadow public abstract String name();

    @Redirect(method = "pollTask", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;doRunTask(Ljava/lang/Runnable;)V"))
    private void redirectExecuteTask(BlockableEventLoop<R> threadExecutor, R task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("Error executing task on {}", this.name(), t);
        }
    }

}
