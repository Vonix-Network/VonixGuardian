package network.vonix.threadedhorizons.mixin.optimization.reduce_allocs;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ResourceLocation.class)
public class MixinIdentifier {

    @Shadow @Final protected String namespace;
    @Shadow @Final protected String path;
    @Unique
    private String cachedString = null;

    /**
     * @author ishland
     * @reason cache toString
     */
    @Overwrite
    public String toString() {
        if (this.cachedString != null) return this.cachedString;
        final String s = this.namespace + ":" + this.path;
        this.cachedString = s;
        return s;
    }

}
