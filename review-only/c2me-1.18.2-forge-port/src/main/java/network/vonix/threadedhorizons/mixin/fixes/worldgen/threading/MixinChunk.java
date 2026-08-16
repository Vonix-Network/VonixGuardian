package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ChunkAccess.class)
public abstract class MixinChunk {

    @Mutable
    @Shadow
    @Final
    private Map<ConfiguredStructureFeature<?, ?>, StructureStart> structureStarts;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.structureStarts = new ConcurrentHashMap<>(this.structureStarts);
    }
}
