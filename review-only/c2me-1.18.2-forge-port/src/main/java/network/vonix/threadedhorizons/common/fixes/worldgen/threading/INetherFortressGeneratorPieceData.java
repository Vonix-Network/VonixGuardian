package network.vonix.threadedhorizons.common.fixes.worldgen.threading;

import java.util.concurrent.atomic.AtomicInteger;

public interface INetherFortressGeneratorPieceData {

    AtomicInteger getGeneratedCountAtomic();

}
