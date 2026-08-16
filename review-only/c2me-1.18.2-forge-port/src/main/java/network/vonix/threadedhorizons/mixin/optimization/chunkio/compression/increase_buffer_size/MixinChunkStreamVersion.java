package network.vonix.threadedhorizons.mixin.optimization.chunkio.compression.increase_buffer_size;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

@SuppressWarnings("InvalidInjectorMethodSignature")
@Mixin(RegionFileVersion.class)
public class MixinChunkStreamVersion {

    @SuppressWarnings({"InvalidMemberReference", "MixinAnnotationTarget"})
    @Dynamic
    @Redirect(method = "<clinit>", at = @At(value = "NEW", target = "(ILnet/minecraft/world/level/chunk/storage/RegionFileVersion$StreamWrapper;Lnet/minecraft/world/level/chunk/storage/RegionFileVersion$StreamWrapper;)Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;"))
    private static RegionFileVersion redirectChunkStreamVersionConstructor(int id, RegionFileVersion.StreamWrapper<InputStream> inputStreamWrapper, RegionFileVersion.StreamWrapper<OutputStream> outputStreamWrapper) {
        if (id == 1) { // GZIP
            return new RegionFileVersion(id, in -> new GZIPInputStream(in, 16 * 1024), out -> new GZIPOutputStream(out, 16 * 1024));
        } else if (id == 2) { // DEFLATE
            return new RegionFileVersion(id, in -> new InflaterInputStream(in, new Inflater(), 16 * 1024), out -> new DeflaterOutputStream(out, new Deflater(), 16 * 1024));
        } else if (id == 3) { // UNCOMPRESSED
            return new RegionFileVersion(id, BufferedInputStream::new, BufferedOutputStream::new);
        } else {
            return new RegionFileVersion(id, inputStreamWrapper, outputStreamWrapper);
        }
    }

}
