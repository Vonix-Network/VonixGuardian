package network.vonix.threadedhorizons.asm;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import network.vonix.threadedhorizons.platform.MappingHooks;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Optional;

public class ASMTransformerLithiumChunkAccessWorkaround {

    private static final String INTERMEDIARY = "intermediary";
    private static final MappingHooks mappingResolver = MappingHooks.resolver();
    private static final String ServerChunkCache = mappingResolver.mapClassName(INTERMEDIARY, "net/minecraft/server/level/ServerChunkCache".replace('/', '.')).replace('.', '/');

    private ASMTransformerLithiumChunkAccessWorkaround() {
    }

    // private getChunkOffThread(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;
    // INVOKEVIRTUAL java/util/concurrent/CompletableFuture.join ()Ljava/lang/Object;
    static void transform(ClassNode classNode) {
        if (!ThreadedHorizonsConfig.generalOptimizationsConfig.optimizeAsyncChunkRequest) return;
        try {
            if (classNode.name.equals(ServerChunkCache) && LoaderHooks.isLithiumFamilyLoaded()) {
                for (MethodNode method : classNode.methods) {
                    if (method.name.equals("th$getChunkOffThread") && method.desc.equals(ASMTransformerMakeVolatile.remapMethodDescriptor("(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))) {
                        ASMMixinPlugin.LOGGER.debug("Replacing lithium-family chunk_access method getChunkOffThread to apply non-blocking async chunk request");
                        final Optional<MethodNode> getChunkOffThread = classNode.methods.stream().filter(methodNode -> methodNode.name.equals("getChunkOffThread")).findAny();
                        getChunkOffThread.ifPresentOrElse(oldMethodNode -> {
                            final MethodNode newMethodNode = new MethodNode();
                            method.accept(newMethodNode);
                            newMethodNode.name = oldMethodNode.name;
                            newMethodNode.access = method.access;
                            newMethodNode.desc = method.desc;
                            newMethodNode.signature = method.signature;
                            newMethodNode.exceptions = new ArrayList<>(method.exceptions);
                            if (method.attrs != null) newMethodNode.attrs = new ArrayList<>(method.attrs);
                            newMethodNode.tryCatchBlocks = new ArrayList<>(method.tryCatchBlocks);
                            classNode.methods.remove(oldMethodNode);
                            classNode.methods.add(newMethodNode);
                        }, () -> {
                            ASMMixinPlugin.LOGGER.warn("lithium-family getChunkOffThread not found");
                        });
                        break;
                    }
                }

            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

}
