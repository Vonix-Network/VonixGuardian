package network.vonix.threadedhorizons.asm;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import network.vonix.threadedhorizons.platform.MappingHooks;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ListIterator;
import java.util.Map;

public class ASMTransformerNbtOpsMapBuilderFastUtilMap {

    private static final String INTERMEDIARY = "intermediary";
    private static final MappingHooks mappingResolver = MappingHooks.resolver();
    private static final String NbtOps$MapBuilder = "net/minecraft/nbt/NbtOps$NbtRecordBuilder";
    private static final String NbtOps$MapBuilderMapped = mappingResolver.mapClassName(INTERMEDIARY, NbtOps$MapBuilder.replace('/', '.')).replace('.', '/');
    private static final String buildDesc = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/nbt/Tag;)Lcom/mojang/serialization/DataResult;";
    private static final String buildDescMapped = ASMTransformerMakeVolatile.remapMethodDescriptor(buildDesc);
    private static final String build = mappingResolver.mapMethodName(INTERMEDIARY, NbtOps$MapBuilder.replace('/', '.'), "build", buildDesc);

    //    NEW net/minecraft/nbt/CompoundTag
    //    DUP
    //    ALOAD 2
    //    CHECKCAST net/minecraft/nbt/CompoundTag
    //    INVOKEVIRTUAL net/minecraft/nbt/CompoundTag.toMap ()Ljava/util/Map;
    //    INVOKESTATIC com/google/common/collect/Maps.newHashMap (Ljava/util/Map;)Ljava/util/HashMap;   <---
    //    INVOKESPECIAL net/minecraft/nbt/CompoundTag.<init> (Ljava/util/Map;)V
    //    ASTORE 3

    public static void transform(ClassNode classNode) {
        try {
            if (classNode.name.equals(NbtOps$MapBuilderMapped)) {
                for (MethodNode method : classNode.methods) {
                    if (method.name.equals(build) && method.desc.equals(buildDescMapped)) {
                        ASMMixinPlugin.LOGGER.debug("Replacing NbtOps$MapBuilder build method newHashMap to fastutil map");
                        final ListIterator<AbstractInsnNode> iterator = method.instructions.iterator();
                        boolean patched = false;
                        while (iterator.hasNext()) {
                            final AbstractInsnNode next = iterator.next();
                            if (next instanceof MethodInsnNode methodInsnNode) {
                                if (methodInsnNode.getOpcode() == Opcodes.INVOKESTATIC &&
                                        methodInsnNode.owner.equals("com/google/common/collect/Maps") &&
                                        methodInsnNode.name.equals("newHashMap") &&
                                        methodInsnNode.desc.equals("(Ljava/util/Map;)Ljava/util/HashMap;")) {
                                    iterator.set(new MethodInsnNode(
                                            Opcodes.INVOKESTATIC,
                                            Type.getInternalName(ASMTransformerNbtOpsMapBuilderFastUtilMap.class),
                                            "newFastUtilMap",
                                            Type.getMethodDescriptor(ASMTransformerNbtOpsMapBuilderFastUtilMap.class.getMethod("newFastUtilMap", Map.class))
                                    ));
                                    patched = true;
                                }
                            }
                        }
                        if (!patched) ASMMixinPlugin.LOGGER.warn("Unable to find target opcode in NbtOps$MapBuilder");
                    }
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static <K, V> Map<K, V> newFastUtilMap(Map<K, V> map) {
        return new Object2ObjectOpenHashMap<>(map);
    }

}
