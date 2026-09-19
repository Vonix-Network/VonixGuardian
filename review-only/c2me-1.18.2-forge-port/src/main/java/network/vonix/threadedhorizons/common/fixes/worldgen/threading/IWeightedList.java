package network.vonix.threadedhorizons.common.fixes.worldgen.threading;

import net.minecraft.world.entity.ai.behavior.ShufflingList;

public interface IWeightedList<U> {

    public ShufflingList<U> shuffleVanilla();

}
