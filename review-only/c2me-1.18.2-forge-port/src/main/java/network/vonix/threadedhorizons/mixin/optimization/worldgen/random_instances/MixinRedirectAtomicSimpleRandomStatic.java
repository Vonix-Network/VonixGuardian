package network.vonix.threadedhorizons.mixin.optimization.worldgen.random_instances;

import network.vonix.threadedhorizons.common.optimization.worldgen.random_instances.SimplifiedAtomicSimpleRandom;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = {
        JigsawPlacement.class
})
public class MixinRedirectAtomicSimpleRandomStatic {

    @Redirect(method = "*", at = @At(value = "NEW", target = "net/minecraft/world/level/levelgen/LegacyRandomSource"))
    private static LegacyRandomSource redirectAtomicSimpleRandom(long l) {
        return new SimplifiedAtomicSimpleRandom(l);
    }

}
