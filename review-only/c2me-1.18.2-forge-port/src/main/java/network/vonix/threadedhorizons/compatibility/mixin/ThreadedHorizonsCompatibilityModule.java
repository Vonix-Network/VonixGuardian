package network.vonix.threadedhorizons.compatibility.mixin;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import network.vonix.threadedhorizons.common.config.ConfigUtils;
import network.vonix.threadedhorizons.compatibility.common.asm.ASMTransformer;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;
import org.spongepowered.asm.mixin.refmap.ReferenceMapper;
import org.spongepowered.asm.mixin.refmap.RemappingReferenceMapper;
import org.spongepowered.asm.mixin.transformer.Config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ThreadedHorizonsCompatibilityModule implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Compatibility Module");
    private static final String mixinPackage = "network.vonix.threadedhorizons.compatibility.mixin.";
    private static final HashSet<String> enabledSubPackages = new HashSet<>();
    private static final HashSet<String> enabledMods = new HashSet<>();
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean isRefMapPrepared = new AtomicBoolean(false);

    public static Set<String> getEnabledMods() {
        return Collections.unmodifiableSet(enabledMods);
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (!initialized.compareAndSet(false, true)) throw new IllegalStateException("Already initialized");
        LOGGER.info("Initializing Threaded Horizons Compatibility Module");
        CommentedFileConfig config = CommentedFileConfig.builder(LoaderHooks.getConfigDir().resolve("threadedhorizons-compat.toml"))
                .autosave()
                .preserveInsertionOrder()
                .sync()
                .build();
        config.load();
        final ConfigUtils.ConfigScope configScope = new ConfigUtils.ConfigScope(config);
//        addMixins("terra", ">=5.0.0", "terra", configScope);
//        addMixins("betterend", ">=0.10.2-pre", "betterend", configScope);
//        addMixins("the_bumblezone", ">=3.0.4+1.17", "thebumblezone", configScope);
//        addMixins("betternether", ">=5.1.3", "betternether", configScope);
//        addMixins("charm", ">=3.3.2", "charm", configScope);
        configScope.removeUnusedKeys();
        config.save();
        config.close();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(mixinPackage)) {
            LOGGER.warn("Attempted to call shouldApplyMixin for foreign mixin {}", mixinClassName);
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        final ArrayList<String> extraMixins = new ArrayList<>();
        for (String subPackage : enabledSubPackages) {
            final String name = "threadedhorizons-compat." + subPackage + ".mixins.json";
            try (final InputStream in = ThreadedHorizonsCompatibilityModule.class.getClassLoader().getResourceAsStream(name);
                 final InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(in, "Cannot find specified module mixins"))) {
                final JsonObject jsonObject = new Gson().fromJson(reader, JsonObject.class);
                for (JsonElement element : jsonObject.getAsJsonArray("mixins")) {
                    extraMixins.add(subPackage + "." + element.getAsString());
                }
            } catch (Throwable t) {
                LOGGER.warn("Unable to apply compatibility module " + subPackage, t);
            }
        }
        LOGGER.info("Adding mixins: {}", extraMixins.isEmpty() ? "[None]" : "");
        for (String s : extraMixins) {
            LOGGER.info("- {}", s);
        }

        checkInjectReferenceMaps();

        return extraMixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        ASMTransformer.transform(targetClass);
    }

    private void addMixins(String modid, String versionRange, String subPackage, ConfigUtils.ConfigScope configScope) {
        if (!LoaderHooks.isModLoaded(modid)) {
            return;
        }
        String version = LoaderHooks.getModVersion(modid).orElse("unknown");
        if (ConfigUtils.getValue(
                configScope,
                subPackage,
                () -> true,
                String.format("Compatibility module for %s@%s(%s)", modid, version, versionRange),
                List.of(),
                false, false)) {
            LOGGER.info("Adding compatibility module for {}@{}({})", modid, version, versionRange);
            enabledSubPackages.add(subPackage);
            enabledMods.add(modid + "@" + version);
        } else {
            LOGGER.info("Not adding compatibility module for {}@{}({}) (disabled in config)", modid, version, versionRange);
        }
    }

    private void checkInjectReferenceMaps() {
        if (!isRefMapPrepared.compareAndSet(false, true)) return;
        if (MixinEnvironment.getCurrentEnvironment().getOption(MixinEnvironment.Option.DISABLE_REFMAP)) {
            LOGGER.info("Skipping refmap injection");
            return;
        }
        try {
            // find mixin config
            final IMixinConfig mixinConfig = ((Map<String, Config>) accessible(Config.class.getDeclaredField("allConfigs")).get(null)).get("threadedhorizons-compat.mixins.json").getConfig();
            if (mixinConfig == null) {
                throw new IllegalStateException("Unable to find mixin config");
            }
            // obtain reference mapper
            IReferenceMapper rootRefMapper = (IReferenceMapper) accessible(Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig").getDeclaredField("refMapper")).get(mixinConfig);
            while (rootRefMapper instanceof RemappingReferenceMapper) {
                rootRefMapper = (IReferenceMapper) accessible(RemappingReferenceMapper.class.getDeclaredField("refMap")).get(rootRefMapper);
            }
            if (rootRefMapper instanceof ReferenceMapper rootRefMapper1) {
                if (rootRefMapper1.isDefault()) {
                    LOGGER.warn("Found default reference mapper, skipping init");
                    return;
                }
                // get reference mapper of all loaded modules
                final Set<ReferenceMapper> childRefMappers = enabledSubPackages.stream()
                        .map(s -> ReferenceMapper.read(String.format("threadedhorizons-compat-%s-refmap.json", s)))
                        .collect(Collectors.toSet());
                // iterate
                for (ReferenceMapper childRefMapper : childRefMappers) {
                    LOGGER.info("Injecting refmap {}", childRefMapper.getResourceName());
                    // merge data
                    final Map<String, Map<String, Map<String, String>>> childMap = (Map<String, Map<String, Map<String, String>>>) accessible(ReferenceMapper.class.getDeclaredField("data")).get(childRefMapper);
                    childMap.forEach((context, _children1) -> _children1.forEach((className, _children2) -> _children2.forEach((reference, newReference) -> {
                        rootRefMapper1.addMapping(null, className, reference, newReference);
                        rootRefMapper1.addMapping(context, className, reference, newReference);
                    })));
                }
                // tell users that this refMapper is injected
                final StringBuilder stringBuilder = new StringBuilder();
                Stream.concat(Stream.of(rootRefMapper1), childRefMappers.stream()).forEach(childRefMapper -> stringBuilder.append(childRefMapper.getResourceName()).append(", "));
                stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                stringBuilder.append(" <threadedhorizons-compat injected>");
                accessible(ReferenceMapper.class.getDeclaredField("resource")).set(rootRefMapper1, stringBuilder.toString());
            } else {
                throw new IllegalArgumentException("Unknown reference mapper: " + rootRefMapper.getClass().getName());
            }
        } catch (Throwable t) {
            throw new RuntimeException("An unexpected error occurred while injecting reference maps", t);
        }
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

}
