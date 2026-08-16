package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.INetherFortressGeneratorPieceData;
import net.minecraft.world.level.levelgen.structure.NetherBridgePieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(NetherBridgePieces.PieceWeight.class)
public class MixinNetherFortressGeneratorPieceData implements INetherFortressGeneratorPieceData {

    private final AtomicInteger placeCountAtomic = new AtomicInteger();

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;placeCount:I", opcode = Opcodes.GETFIELD))
    private int redirectGetGeneratedCount(NetherBridgePieces.PieceWeight pieceData) {
        return this.placeCountAtomic.get();
    }

    @SuppressWarnings("MixinAnnotationTarget")
    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD), require = 0)
    private void redirectSetGeneratedCount(NetherBridgePieces.PieceWeight pieceData, int value) {
        this.placeCountAtomic.set(value);
    }

    @Override
    public AtomicInteger getGeneratedCountAtomic() {
        return this.placeCountAtomic;
    }
}
