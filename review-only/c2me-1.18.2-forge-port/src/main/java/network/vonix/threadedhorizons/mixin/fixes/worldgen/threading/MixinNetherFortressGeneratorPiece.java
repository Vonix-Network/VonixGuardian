package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.INetherFortressGeneratorPieceData;
import net.minecraft.world.level.levelgen.structure.NetherBridgePieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NetherBridgePieces.NetherBridgePiece.class)
public class MixinNetherFortressGeneratorPiece {

    @Redirect(method = "updatePieceWeight", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;placeCount:I", opcode = Opcodes.GETFIELD))
    private int redirectGetPieceDataGeneratedCount(NetherBridgePieces.PieceWeight pieceData) {
        return ((INetherFortressGeneratorPieceData) pieceData).getGeneratedCountAtomic().get();
    }

    @Redirect(method = "generatePiece", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD))
    private void redirectIncrementPieceDataGeneratedCount(NetherBridgePieces.PieceWeight pieceData, int value) {
        ((INetherFortressGeneratorPieceData) pieceData).getGeneratedCountAtomic().incrementAndGet();
    }

}
