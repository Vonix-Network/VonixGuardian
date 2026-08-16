package network.vonix.threadedhorizons.client.mixin;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class ThreadedHorizonsClientMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Client Mixin");

    @Override
    public void onLoad(String mixinPackage) {
        LOGGER.info("TH_CLIENT_MIXIN_PLUGIN distClient={}", LoaderHooks.isClient());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!LoaderHooks.isClient()) {
            LOGGER.info("TH_MIXIN_SKIPPED {} -> {} reason=not-client", mixinClassName, targetClassName);
            return false;
        }
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.client.mixin.uncapvd.")) {
            boolean apply = ThreadedHorizonsConfig.clientSideConfig.modifyMaxVDConfig.enabled;
            if (!apply) {
                LOGGER.info("TH_MIXIN_SKIPPED {} -> {} reason=uncap-disabled", mixinClassName, targetClassName);
            }
            return apply;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        LOGGER.info("TH_MIXIN_APPLIED {} -> {}", mixinClassName, targetClassName);
    }
}
