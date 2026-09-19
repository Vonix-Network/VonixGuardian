package network.vonix.threadedhorizons.mixin.fixes.worldgen.graal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

@Mixin(PlacedFeature.class)
public class MixinPlacedFeature {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow @Final private List<PlacementModifier> placement;

    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;

    /**
     * @author ishland
     * @reason retry when stream fails
     */
    @Overwrite
    private boolean placeWithContext(PlacementContext context, Random random, BlockPos pos) {
        Stream<BlockPos> stream;
        for (int retries = 1; ; retries ++) {
            try {
                stream = Stream.of(pos);

                for(PlacementModifier placementModifier : this.placement) {
                    stream = stream.flatMap(posx -> placementModifier.getPositions(context, random, posx));
                }

                break;
            } catch (IllegalStateException e) {
                if (e.getMessage().equals("stream has already been operated upon or closed")) {
                    if (retries == 3) {
                        System.err.println("Retry failed, throwing exception");
                        throw e;
                    }
                    System.err.println(String.format("Possible graalvm issue, retrying... (attempt %d)", retries + 1));
                    e.printStackTrace();
                }
            }
        }

        ConfiguredFeature<?, ?> configuredFeature = this.feature.value();
        if (configuredFeature == null) {
            LOGGER.error("NULL configuredFeature in PlacedFeature {}", this, new Throwable());
            return false;
        }
        MutableBoolean placementModifier = new MutableBoolean();
        stream.forEach(blockPos -> {
            if (configuredFeature.place(context.getLevel(), context.generator(), random, blockPos)) {
                placementModifier.setTrue();
            }

        });
        return placementModifier.isTrue();
    }

}
