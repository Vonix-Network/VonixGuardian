package network.vonix.threadedhorizons.asm;

import network.vonix.threadedhorizons.platform.LoaderHooks;
import network.vonix.threadedhorizons.platform.MappingHooks;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ASMTransformerMakeVolatile {

    private static final String INTERMEDIARY = "intermediary";

    private static final Map<String, List<String>> makeVolatileFields = new HashMap<>();
    private static final Map<String, List<String>> makeVolatileFieldsMapped;
    private static final MappingHooks mappingResolver = MappingHooks.resolver();

    static {
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/MineShaftPieces$MineShaftCorridor", // net/minecraft/world/level/levelgen/structure/MineShaftPieces$MineShaftCorridor
                List.of(
                        "hasPlacedSpider:Z" // hasSpawner:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/NetherBridgePieces$MonsterThrone", // net/minecraft/world/level/levelgen/structure/NetherBridgePieces$MonsterThrone
                List.of(
                        "hasPlacedSpawner:Z" // hasBlazeSpawner:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/NetherBridgePieces$CastleSmallCorridorLeftTurnPiece", // net/minecraft/world/level/levelgen/structure/NetherBridgePieces$CastleSmallCorridorLeftTurnPiece
                List.of(
                        "isNeedingChest:Z" // containsChest:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/NetherBridgePieces$CastleSmallCorridorRightTurnPiece", // net/minecraft/world/level/levelgen/structure/NetherBridgePieces$CastleSmallCorridorRightTurnPiece
                List.of(
                        "isNeedingChest:Z" // containsChest:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/NetherBridgePieces$StartPiece", // net/minecraft/world/level/levelgen/structure/NetherBridgePieces$StartPiece
                List.of(
                        "previousPiece:Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;" // lastPiece:Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/OceanMonumentPieces$RoomDefinition", // net/minecraft/world/level/levelgen/structure/OceanMonumentPieces$RoomDefinition
                List.of(
                        "claimed:Z", // used:Z
                        "field_14484:Z", // not mapped by yarn
                        "field_14483:I"  // not mapped by yarn
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/OceanMonumentPieces$MonumentBuilding", // net/minecraft/world/level/levelgen/structure/OceanMonumentPieces$MonumentBuilding
                List.of(
                        "field_14464:Lnet/minecraft/world/level/levelgen/structure/OceanMonumentPieces$RoomDefinition;", // not mapped by yarn
                        "field_14466:Lnet/minecraft/world/level/levelgen/structure/OceanMonumentPieces$RoomDefinition;"  // not mapped by yarn
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/TemplateStructurePiece", // net/minecraft/world/level/levelgen/structure/TemplateStructurePiece
                List.of(
                        "templatePosition:Lnet/minecraft/core/BlockPos;" // pos:Lnet/minecraft/core/BlockPos;
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/StrongholdPieces$ChestCorridor", // net/minecraft/world/level/levelgen/structure/StrongholdPieces$ChestCorridor
                List.of(
                        "hasPlacedChest:Z" // chestGenerated:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/StrongholdPieces$PortalRoom", // net/minecraft/world/level/levelgen/structure/StrongholdPieces$PortalRoom
                List.of(
                        "hasPlacedSpawner:Z" // spawnerPlaced:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/StrongholdPieces$StartPiece", // net/minecraft/world/level/levelgen/structure/StrongholdPieces$StartPiece
                List.of(
                        "previousPiece:Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces$PieceWeight;", // lastPiece:Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces$PieceWeight;
                        "portalRoomPiece:Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces$PortalRoom;"  // portalRoom:Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces$PortalRoom;
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/ScatteredFeaturePiece", // net/minecraft/world/level/levelgen/structure/StructurePieceWithDimensions
                List.of(
                        "heightPosition:I" // hPos:I
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/SwamplandHutPiece", // net/minecraft/world/level/levelgen/structure/SwamplandHutPiece
                List.of(
                        "spawnedWitch:Z", // hasWitch:Z
                        "spawnedCat:Z"  // hasCat:Z
                )
        );
        makeVolatileFields.put(
                "net/minecraft/world/level/levelgen/structure/WoodlandMansionPieces$PlacementData", // net/minecraft/world/level/levelgen/structure/WoodlandMansionPieces$PlacementData
                List.of(
                        "rotation:Lnet/minecraft/world/level/block/Rotation;", // rotation:Lnet/minecraft/world/level/block/Rotation;
                        "position:Lnet/minecraft/core/BlockPos;", // position:Lnet/minecraft/core/BlockPos;
                        "wallType:Ljava/lang/String;"          // template:Ljava/lang/String;
                )
        );

        makeVolatileFieldsMapped = makeVolatileFields.entrySet().stream()
                .map(entry -> {
                    String mappedClassName = mappingResolver.mapClassName(INTERMEDIARY, entry.getKey().replace('/', '.')).replace('.', '/');
                    List<String> mappedFieldNames = entry.getValue().stream()
                            .map(fieldName -> {
                                String[] split = fieldName.split(":");
                                return mappingResolver.mapFieldName(INTERMEDIARY, entry.getKey().replace('/', '.'), split[0], split[1]) + ":" + remapFieldDescriptor(split[1]);
                            }).toList();
                    return new KeyValue<>(mappedClassName, mappedFieldNames);
                }).collect(Collectors.toMap(KeyValue::key, KeyValue::value));
    }

    static String remapMethodDescriptor(String desc) {
        final Type returnType = Type.getReturnType(desc);
        final Type[] argumentTypes = Type.getArgumentTypes(desc);
        return Type.getMethodDescriptor(
                Type.getType(remapFieldDescriptor(returnType.getDescriptor())),
                Arrays.stream(argumentTypes)
                        .map(type -> Type.getType(remapFieldDescriptor(type.getDescriptor())))
                        .toArray(Type[]::new)
        );
    }

    static String remapFieldDescriptor(String desc) {
        final Type type = Type.getType(desc);
        if (type.getSort() == Type.ARRAY) { // remap arrays
            return "[".repeat(type.getDimensions()) + remapFieldDescriptor(type.getElementType().getDescriptor());
        }
        if (type.getSort() != Type.OBJECT) { // no need to remap primitives
            return desc;
        }
        final String unmappedClassDesc = type.getClassName();
        final String unmappedClass;
        if (unmappedClassDesc.endsWith(";") && unmappedClassDesc.startsWith("L")) {
            unmappedClass = unmappedClassDesc.substring(1, unmappedClassDesc.length() - 1); // trim starting "L" and ending ";"
        } else {
            unmappedClass = unmappedClassDesc;
        }
        return 'L' + mappingResolver.mapClassName(INTERMEDIARY, unmappedClass.replace('/', '.')).replace('.', '/') + ";";
    }

    public static void transform(ClassNode classNode) {
        final List<String> pendingFields = makeVolatileFieldsMapped.get(classNode.name);
        if (pendingFields != null) {
            ASMMixinPlugin.LOGGER.debug("Transforming class {}", classNode.name.replace('/', '.'));
            classNode.fields.stream()
                    .filter(fieldNode -> pendingFields.contains(fieldNode.name + ":" + fieldNode.desc))
                    .forEach(fieldNode -> {
                        ASMMixinPlugin.LOGGER.debug("Making field L{};{}:{} volatile", classNode.name, fieldNode.name, fieldNode.desc);
                        fieldNode.access |= Opcodes.ACC_VOLATILE;
                    });
        }
    }

    private record KeyValue<K, V>(K key, V value) {
    }

}
