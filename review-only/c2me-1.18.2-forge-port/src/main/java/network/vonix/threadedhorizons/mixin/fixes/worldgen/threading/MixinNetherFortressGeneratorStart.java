package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.INetherFortressGeneratorPieceData;
import net.minecraft.world.level.levelgen.structure.NetherBridgePieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(NetherBridgePieces.StartPiece.class)
public class MixinNetherFortressGeneratorStart {

    @Shadow public List<NetherBridgePieces.PieceWeight> availableBridgePieces;
    @Shadow public List<NetherBridgePieces.PieceWeight> availableCastlePieces;

    @Redirect(method = "<init>(Ljava/util/Random;II)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD))
    private void redirectSetPieceDataGeneratedCount(NetherBridgePieces.PieceWeight pieceData, int value) {
        ((INetherFortressGeneratorPieceData) pieceData).getGeneratedCountAtomic().set(value);
    }

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.availableBridgePieces = Collections.synchronizedList(this.availableBridgePieces);
        this.availableCastlePieces = Collections.synchronizedList(this.availableCastlePieces);
    }
}
