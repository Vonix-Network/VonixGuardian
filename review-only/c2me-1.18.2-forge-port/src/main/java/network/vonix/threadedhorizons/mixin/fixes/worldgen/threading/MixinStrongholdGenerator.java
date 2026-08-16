package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.IStrongholdGenerator;
import net.minecraft.world.level.levelgen.structure.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Mixin(StrongholdPieces.class)
public class MixinStrongholdGenerator implements IStrongholdGenerator {

    @Shadow @Final private static StrongholdPieces.PieceWeight[] STRONGHOLD_PIECE_WEIGHTS;
    @Shadow private static List<StrongholdPieces.PieceWeight> currentPieces;
    private static final ThreadLocal<Integer> totalWeightThreadLocal = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Class<? extends StrongholdPieces.StrongholdPiece>> activePieceTypeThreadLocal = new ThreadLocal<>();

    @Redirect(method = "resetPieces", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;currentPieces:Ljava/util/List;", opcode = Opcodes.PUTSTATIC))
    private static void redirectAssignList(List<StrongholdPieces.PieceWeight> value) {
        currentPieces = Collections.synchronizedList(value);
        final List<StrongholdPieces.PieceWeight> pieceDataList = Arrays.asList(STRONGHOLD_PIECE_WEIGHTS);
        pieceDataList.forEach(pieceData -> pieceData.placeCount = 0);
        currentPieces.addAll(pieceDataList);
    }

    @Redirect(method = "resetPieces", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD))
    private static void redirectSetGeneratedCount(StrongholdPieces.PieceWeight pieceData, int value) {
        // no-op
    }

    @Redirect(method = "resetPieces", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private static <E> boolean redirectListAdd(List<E> list, E e) {
        return false; // no-op
    }

    @Redirect(method = "updatePieceWeight", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;totalWeight:I", opcode = Opcodes.PUTSTATIC))
    private static void redirectSetTotalWeight(int value) {
        totalWeightThreadLocal.set(value);
    }

    @Redirect(method = {"generatePieceFromSmallDoor", "updatePieceWeight"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;totalWeight:I", opcode = Opcodes.GETSTATIC))
    private static int redirectGetTotalWeight() {
        return totalWeightThreadLocal.get();
    }

    @Redirect(method = "generatePieceFromSmallDoor", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;imposedPiece:Ljava/lang/Class;", opcode = Opcodes.PUTSTATIC))
    private static void redirectSetActivePieceType(Class<? extends StrongholdPieces.StrongholdPiece> value) {
        activePieceTypeThreadLocal.set(value);
    }

    @Redirect(method = "generatePieceFromSmallDoor", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;imposedPiece:Ljava/lang/Class;", opcode = Opcodes.GETSTATIC))
    private static Class<? extends StrongholdPieces.StrongholdPiece> redirectGetActivePieceType() {
        return activePieceTypeThreadLocal.get();
    }

    @Override
    public ThreadLocal<Class<? extends StrongholdPieces.StrongholdPiece>> getActivePieceTypeThreadLocal() {
        return activePieceTypeThreadLocal;
    }
}
