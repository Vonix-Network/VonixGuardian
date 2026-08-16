package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.IWeightedList;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Mixin(ShufflingList.class)
public class MixinWeightedList<U> implements IWeightedList<U> {

    @Shadow @Final public List<ShufflingList.WeightedEntry<U>> entries;

    @Shadow @Final private Random random;

    /**
     * @author ishland
     * @reason create new instance on shuffling
     */
    @Overwrite
    public ShufflingList<U> shuffle() {
        final ShufflingList<U> newList = new ShufflingList<>(entries); // C2ME - use new instance
        final Random random = new Random(); // C2ME - use new instance
        newList.entries.forEach((entry) -> { // C2ME - use new instance
            entry.setRandom(random.nextFloat());
        });
        newList.entries.sort(Comparator.comparingDouble((object) -> { // C2ME - use new instance
            return ((ShufflingList.WeightedEntry)object).getRandWeight();
        }));
        return newList; // C2ME - use new instance
    }

    @Override
    public ShufflingList<U> shuffleVanilla() {
        this.entries.forEach((entry) -> {
            entry.setRandom(this.random.nextFloat());
        });
        this.entries.sort(Comparator.comparingDouble(ShufflingList.WeightedEntry::getRandWeight));
        return (ShufflingList<U> ) (Object) this;
    }
}
