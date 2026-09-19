package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    @Shadow
    private ChunkGenerator generator;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        GlobalExecutors.executor.execute(() -> {
            try {
                this.generator.ensureStructuresGenerated();
            } catch (Throwable error) {
                org.slf4j.LoggerFactory.getLogger("Threaded Horizons").warn(
                        "Async structure warmup did not complete; generation will retry on demand",
                        error
                );
            }
        });
    }

}
