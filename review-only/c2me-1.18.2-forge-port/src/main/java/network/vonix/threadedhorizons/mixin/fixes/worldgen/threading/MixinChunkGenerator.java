package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator {

    @Shadow private boolean hasGeneratedPositions;

    @Shadow protected abstract void generatePositions();

    /**
     * @author ishland
     * @reason synchronize stronghold position generation
     */
    @Overwrite
    public void ensureStructuresGenerated() {
        network.vonix.threadedhorizons.common.worldgen.StructureGeneration.ensureGenerated(
                this,
                () -> this.hasGeneratedPositions,
                value -> this.hasGeneratedPositions = value,
                () -> {
                    System.out.println("Initializing stronghold positions, this may take a while");
                    this.generatePositions();
                    System.out.println("Stronghold positions initialized");
                }
        );
    }

}
