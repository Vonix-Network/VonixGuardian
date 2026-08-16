package network.vonix.threadedhorizons.common.fixes.worldgen.threading;

import net.minecraft.world.level.levelgen.structure.StrongholdPieces;

public interface IStrongholdGenerator {

    ThreadLocal<Class<? extends StrongholdPieces.StrongholdPiece>> getActivePieceTypeThreadLocal();

    class Holder {
        @SuppressWarnings({"InstantiationOfUtilityClass", "ConstantConditions"})
        public static final IStrongholdGenerator INSTANCE = (IStrongholdGenerator) new StrongholdPieces();
    }

}
