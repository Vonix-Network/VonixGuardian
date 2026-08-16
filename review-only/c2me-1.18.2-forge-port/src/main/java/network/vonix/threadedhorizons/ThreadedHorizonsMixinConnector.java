package network.vonix.threadedhorizons;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

@SuppressWarnings("unused")
public class ThreadedHorizonsMixinConnector implements IMixinConnector {
    @Override
    public void connect() {
        Mixins.addConfiguration("threadedhorizons.mixins.json");
        Mixins.addConfiguration("threadedhorizons-asm.mixins.json");
        Mixins.addConfiguration("threadedhorizons-compat.mixins.json");
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Mixins.addConfiguration("threadedhorizons.client.mixins.json");
        }
    }
}
