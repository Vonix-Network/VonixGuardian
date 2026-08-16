package network.vonix.threadedhorizons.platform;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IRemapper;

/**
 * Official-name first remapper. Forge 1.18.2 keeps official class names at runtime
 * and remaps method/field names to SRG in production.
 */
public final class MappingHooks {
    private MappingHooks() {
    }

    public static MappingHooks resolver() {
        return Holder.INSTANCE;
    }

    public String mapClassName(String fromNs, String name) {
        return name.replace('.', '/').replace('/', '.');
    }

    public String mapFieldName(String fromNs, String owner, String name, String desc) {
        return mapField(owner.replace('.', '/'), name, desc);
    }

    public String mapMethodName(String fromNs, String owner, String name, String desc) {
        return mapMethod(owner.replace('.', '/'), name, desc);
    }

    public static String mapField(String ownerInternal, String officialName, String desc) {
        IRemapper remapper = remapper();
        if (remapper == null) {
            return officialName;
        }
        try {
            String mapped = remapper.mapFieldName(ownerInternal, officialName, desc);
            return mapped == null || mapped.isEmpty() ? officialName : mapped;
        } catch (Throwable throwable) {
            return officialName;
        }
    }

    public static String mapMethod(String ownerInternal, String officialName, String desc) {
        IRemapper remapper = remapper();
        if (remapper == null) {
            return officialName;
        }
        try {
            String mapped = remapper.mapMethodName(ownerInternal, officialName, desc);
            return mapped == null || mapped.isEmpty() ? officialName : mapped;
        } catch (Throwable throwable) {
            return officialName;
        }
    }

    public static String mapClass(String officialInternal) {
        IRemapper remapper = remapper();
        if (remapper == null) {
            return officialInternal;
        }
        try {
            String mapped = remapper.map(officialInternal);
            return mapped == null || mapped.isEmpty() ? officialInternal : mapped;
        } catch (Throwable throwable) {
            return officialInternal;
        }
    }

    private static IRemapper remapper() {
        try {
            return MixinEnvironment.getCurrentEnvironment().getRemappers();
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static final class Holder {
        private static final MappingHooks INSTANCE = new MappingHooks();
    }
}
