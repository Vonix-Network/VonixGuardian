package network.vonix.threadedhorizons.mixin.threading.chunkio;

import com.ibm.asyncutil.locks.AsyncLock;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.LegacyStructureDataHandler;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Supplier;

@Mixin(ChunkStorage.class)
public abstract class MixinVersionedChunkStorage {

    @Shadow @Final protected DataFixer fixerUpper;

    @Shadow @Nullable private LegacyStructureDataHandler legacyStructureHandler;

    @Shadow
    public static int getVersion(CompoundTag nbt) {
        throw new AbstractMethodError();
    }

    @Shadow
    public static void injectDatafixingContext(CompoundTag nbt, ResourceKey<Level> worldKey, Optional<ResourceKey<Codec<? extends ChunkGenerator>>> generatorCodecKey) {
        throw new AbstractMethodError();
    }

    private AsyncLock legacyStructureHandlerLock = AsyncLock.createFair();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.legacyStructureHandlerLock = AsyncLock.createFair();
    }

    /**
     * @author ishland
     * @reason async loading
     */
    @Overwrite
    public CompoundTag upgradeChunkTag(ResourceKey<Level> worldKey, Supplier<DimensionDataStorage> overworldDataStorage, CompoundTag nbt, Optional<ResourceKey<Codec<? extends ChunkGenerator>>> optional) {
        int i = getVersion(nbt);
        int j = 1493;
        if (i < 1493) {
            nbt = NbtUtils.update(this.fixerUpper, DataFixTypes.CHUNK, nbt, i, 1493);
            if (nbt.getCompound("Level").getBoolean("hasLegacyStructureData")) {
                try (AsyncLock.LockToken ignored = this.legacyStructureHandlerLock.acquireLock().toCompletableFuture().join()) {
                    if (this.legacyStructureHandler == null) {
                        this.legacyStructureHandler = LegacyStructureDataHandler.getLegacyStructureHandler(worldKey, overworldDataStorage.get());
                    }

                    nbt = this.legacyStructureHandler.updateFromLegacy(nbt);
                }
            }
        }

        injectDatafixingContext(nbt, worldKey, optional);
        nbt = NbtUtils.update(this.fixerUpper, DataFixTypes.CHUNK, nbt, Math.max(1493, i));
        if (i < SharedConstants.getCurrentVersion().getWorldVersion()) {
            nbt.putInt("DataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        }

        nbt.remove("__context");
        return nbt;
    }

    @Redirect(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/LegacyStructureDataHandler;removeIndex(J)V"))
    private void onSetTagAtFeatureUpdaterMarkResolved(LegacyStructureDataHandler legacyStructureHandler, long l) {
        try (final AsyncLock.LockToken ignored = legacyStructureHandlerLock.acquireLock().toCompletableFuture().join()) {
            legacyStructureHandler.removeIndex(l);
        }
    }

}
