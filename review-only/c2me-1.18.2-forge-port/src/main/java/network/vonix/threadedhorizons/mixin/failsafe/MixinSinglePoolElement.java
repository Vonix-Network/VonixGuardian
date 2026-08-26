package network.vonix.threadedhorizons.mixin.failsafe;

import net.minecraft.core.Holder;
import net.minecraft.data.worldgen.ProcessorLists;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SinglePoolElement.class)
public class MixinSinglePoolElement {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Failsafe");

    @Shadow
    @Final
    protected Holder<StructureProcessorList> processors;

    /**
     * Official 1.18.2 {@code SinglePoolElement.place} does not call
     * {@code Holder.value()}. That invoke is in {@code getSettings}, which
     * {@code place} uses to build {@code StructurePlaceSettings}.
     */
    @Redirect(method = "getSettings", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;value()Ljava/lang/Object;"))
    private Object redirectProcessorValue(Holder<StructureProcessorList> holder) {
        StructureProcessorList list = holder.value();
        if (list == null) {
            LOGGER.error("Null structure processor list while placing {}; using empty list", this.processors);
            return ProcessorLists.EMPTY.value();
        }
        return list;
    }
}
