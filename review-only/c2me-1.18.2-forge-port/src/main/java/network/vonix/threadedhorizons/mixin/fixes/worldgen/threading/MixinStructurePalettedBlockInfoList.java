package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Mixin(StructureTemplate.Palette.class)
public class MixinStructurePalettedBlockInfoList {

    @Mutable
    @Shadow @Final private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.cache = Collections.synchronizedMap(cache);
    }

}
