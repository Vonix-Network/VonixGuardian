package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.IStrongholdGenerator;
import net.minecraft.world.level.levelgen.structure.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StrongholdPieces.StairsDown.class)
public class MixinStrongholdGeneratorSpiralStaircase {

    @Redirect(method = "addChildren", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;imposedPiece:Ljava/lang/Class;", opcode = Opcodes.PUTSTATIC))
    private void redirectGetActivePieceType(Class<? extends StrongholdPieces.StrongholdPiece> value) {
        IStrongholdGenerator.Holder.INSTANCE.getActivePieceTypeThreadLocal().set(value);
    }

}
