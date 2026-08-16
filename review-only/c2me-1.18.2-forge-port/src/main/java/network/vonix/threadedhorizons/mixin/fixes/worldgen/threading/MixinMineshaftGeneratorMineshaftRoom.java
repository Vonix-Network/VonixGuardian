package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import net.minecraft.world.level.levelgen.structure.MineShaftPieces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(MineShaftPieces.MineShaftRoom.class)
public class MixinMineshaftGeneratorMineshaftRoom {

    @Mutable
    @Shadow @Final private List<BoundingBox> childEntranceBoxes;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.childEntranceBoxes = Collections.synchronizedList(this.childEntranceBoxes);
    }

}
