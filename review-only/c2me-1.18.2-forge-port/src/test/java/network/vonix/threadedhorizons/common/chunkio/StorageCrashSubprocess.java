package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Path;

/**
 * Child process used by {@link StorageCrashReopenTest}. Writes one acknowledged
 * generation, prints {@code COMMITTED}, then sleeps until the parent kills it.
 */
public final class StorageCrashSubprocess {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: StorageCrashSubprocess <directory> <value>");
            System.exit(2);
        }
        Path directory = Path.of(args[0]);
        int value = Integer.parseInt(args[1]);
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", value);
        tag.putString("Status", "full");
        VanillaRegionBackend backend = new VanillaRegionBackend(directory, true);
        backend.write(new ChunkPos(0, 0), tag);
        backend.flush();
        backend.close();
        System.out.println("COMMITTED");
        System.out.flush();
        Thread.sleep(60_000L);
    }
}
