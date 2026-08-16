package network.vonix.threadedhorizons.mixin.optimization.chunkio.hide_sync_disk_writes_behind_flag;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
public class MixinRegionBasedStorage {

    @Mutable
    @Shadow @Final private boolean sync;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onPostInit(Path folder, boolean requestedSync, CallbackInfo info) {
        String override = System.getProperty("network.vonix.threadedhorizons.chunkio.syncDiskWrites");
        if (override != null) {
            this.sync = Boolean.parseBoolean(override);
        } else {
            this.sync = requestedSync;
        }
    }

}
