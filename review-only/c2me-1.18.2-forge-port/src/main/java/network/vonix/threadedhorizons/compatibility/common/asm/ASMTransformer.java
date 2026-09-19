package network.vonix.threadedhorizons.compatibility.common.asm;

import network.vonix.threadedhorizons.compatibility.common.ThreadLocalMutableBlockPos;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import network.vonix.threadedhorizons.platform.MappingHooks;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ListIterator;
import java.util.function.Consumer;

public class ASMTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Compatibility Module ASM Transformer");
    private static final MappingHooks mappingResolver = MappingHooks.resolver();
    private static final String INTERMEDIARY = "intermediary";

    private static final String BlockPosMutableName = mappingResolver.mapClassName(INTERMEDIARY, "net/minecraft/core/BlockPos$MutableBlockPos".replace('/', '.')).replace('.', '/');
    private static final String ChunkRandomName = mappingResolver.mapClassName(INTERMEDIARY, "net/minecraft/world/level/levelgen/WorldgenRandom".replace('/', '.')).replace('.', '/');

    public static void transform(ClassNode classNode) {
        final Consumer<MethodNode> transformer = methodNode -> {
            LOGGER.debug("Transforming L{};{}{}", classNode.name, methodNode.name, methodNode.desc);
            final ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();
            while (iterator.hasNext()) {
                final AbstractInsnNode insnNode = iterator.next();
                if (insnNode instanceof TypeInsnNode typeInsnNode) {
                    if (typeInsnNode.getOpcode() == Opcodes.NEW) {
                        if (BlockPosMutableName.equals(typeInsnNode.desc)) {
                            LOGGER.debug("Replacing NEW {} with NEW {}", typeInsnNode.desc, Type.getInternalName(ThreadLocalMutableBlockPos.class));
                            iterator.set(new TypeInsnNode(Opcodes.NEW, Type.getInternalName(ThreadLocalMutableBlockPos.class)));
                        }
//                        else if (ChunkRandomName.equals(typeInsnNode.desc) || "java/util/Random".equals(typeInsnNode.desc)) {
//                            LOGGER.info("Replacing NEW {} with NEW {}", typeInsnNode.desc, Type.getInternalName(ThreadLocalChunkRandom.class));
//                            iterator.set(new TypeInsnNode(Opcodes.NEW, Type.getInternalName(ThreadLocalChunkRandom.class)));
//                        }
                    }
                } else if (insnNode instanceof MethodInsnNode methodInsnNode) {
                    if (methodInsnNode.getOpcode() == Opcodes.INVOKESPECIAL && methodInsnNode.name.equals("<init>")) {
                        if (BlockPosMutableName.equals(methodInsnNode.owner)) {
                            LOGGER.debug("Replacing initializer call of {} with {}", methodInsnNode.owner, Type.getInternalName(ThreadLocalMutableBlockPos.class));
                            iterator.set(new MethodInsnNode(Opcodes.INVOKESPECIAL, Type.getInternalName(ThreadLocalMutableBlockPos.class), "<init>", methodInsnNode.desc));
                        }
//                        else if (ChunkRandomName.equals(methodInsnNode.owner) || "java/util/Random".equals(methodInsnNode.owner)) {
//                            LOGGER.info("Replacing initializer call of {} with {}", methodInsnNode.owner, Type.getInternalName(ThreadLocalChunkRandom.class));
//                            iterator.set(new MethodInsnNode(Opcodes.INVOKESPECIAL, Type.getInternalName(ThreadLocalChunkRandom.class), "<init>", methodInsnNode.desc));
//                        }
                    }
                }
            }
        };
        classNode.methods.stream()
                .filter(methodNode -> methodNode.name.equals("<clinit>") || methodNode.name.equals("<init>"))
                .forEach(transformer);
    }


}
