package network.vonix.threadedhorizons.mixin.chunkio;

import network.vonix.threadedhorizons.common.chunkio.ThreadedHorizonsStorageVanillaInterface;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;

@Mixin(SectionStorage.class)
public class MixinSerializingRegionBasedStorage {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/storage/IOWorker"))
    private IOWorker redirectStorageIoWorker(Path directory, boolean dsync, String name) {
        return new ThreadedHorizonsStorageVanillaInterface(directory, dsync, name);
    }

}
