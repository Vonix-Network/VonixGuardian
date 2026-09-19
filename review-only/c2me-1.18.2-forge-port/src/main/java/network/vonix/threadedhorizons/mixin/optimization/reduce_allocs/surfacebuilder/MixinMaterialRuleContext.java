package network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.surfacebuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(SurfaceRules.Context.class)
public class MixinMaterialRuleContext {

    @Shadow
    @Final
    private Function<BlockPos, Holder<Biome>> biomeGetter;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos pos;

    @Shadow
    private long lastUpdateY;

    @Shadow
    private Supplier<Holder<Biome>> biome;

    @Shadow
    private int blockY;

    @Shadow
    private int waterHeight;

    @Shadow
    private int stoneDepthBelow;

    @Shadow
    private int stoneDepthAbove;

    @Unique
    private int lazyPosX;
    @Unique
    private int lazyPosY;
    @Unique
    private int lazyPosZ;
    @Unique
    private Holder<Biome> lastBiome = null;
    @Unique
    private ResourceKey<Biome> lastBiomeKey = null;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.biome = () -> {
            if (this.lastBiome == null)
                return this.lastBiome = this.biomeGetter.apply(this.pos.set(this.lazyPosX, this.lazyPosY, this.lazyPosZ));
            return this.lastBiome;
        };
    }

    /**
     * @author ishland
     * @reason reduce allocs
     */
    @Overwrite
    protected void updateY(int i, int j, int k, int l, int m, int n) {
        ++this.lastUpdateY;
        this.blockY = m;
        this.waterHeight = k;
        this.stoneDepthBelow = j;
        this.stoneDepthAbove = i;

        // set lazy values
        this.lazyPosX = l;
        this.lazyPosY = m;
        this.lazyPosZ = n;
        // clear cache
        this.lastBiome = null;
        this.lastBiomeKey = null;
    }

}
