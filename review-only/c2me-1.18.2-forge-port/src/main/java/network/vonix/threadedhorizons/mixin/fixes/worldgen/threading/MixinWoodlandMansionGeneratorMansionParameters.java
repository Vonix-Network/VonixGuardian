package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.ConcurrentFlagMatrix;
import net.minecraft.world.level.levelgen.structure.WoodlandMansionPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@SuppressWarnings({"InvalidInjectorMethodSignature", "MixinAnnotationTarget"})
@Mixin(WoodlandMansionPieces.MansionGrid.class)
public class MixinWoodlandMansionGeneratorMansionParameters {

    @SuppressWarnings("UnresolvedMixinReference")
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/world/level/levelgen/structure/WoodlandMansionPieces$SimpleGrid"))
    private WoodlandMansionPieces.SimpleGrid redirectNewMatrix(int n, int m, int fallback) {
        return new ConcurrentFlagMatrix(n, m, fallback);
    }

}
