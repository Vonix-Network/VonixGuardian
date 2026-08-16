package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.IWeightedList;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(GateBehavior.OrderPolicy.class)
public class MixinOrder {

    @Mutable
    @Shadow @Final private Consumer<ShufflingList<?>> consumer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(String enumName, int ordinal, Consumer<ShufflingList<?>> listModifier, CallbackInfo ci) {
        if (enumName.equals("SHUFFLED") || enumName.equals("SHUFFLED"))
            this.consumer = obj -> ((IWeightedList<?>) obj).shuffleVanilla();
    }

}
