package network.vonix.threadedhorizons.mixin;

import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinglePoolElementBytecodeTest {

    @Test
    void holderValueIsInGetSettingsNotPlace() throws Exception {
        String disassembly = TickChunksBytecodeTest.javap(SinglePoolElement.class);
        String place = TickChunksBytecodeTest.extractMethod(disassembly,
                "public boolean place(net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager, net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.StructureFeatureManager, net.minecraft.world.level.chunk.ChunkGenerator, net.minecraft.core.BlockPos, net.minecraft.core.BlockPos, net.minecraft.world.level.block.Rotation, net.minecraft.world.level.levelgen.structure.BoundingBox, java.util.Random, boolean);");
        String getSettings = TickChunksBytecodeTest.extractMethod(disassembly,
                "protected net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings getSettings(net.minecraft.world.level.block.Rotation, net.minecraft.world.level.levelgen.structure.BoundingBox, boolean);");
        assertFalse(place.contains("Holder.value:()Ljava/lang/Object;"),
                "place must not invoke Holder.value on official 1.18.2");
        assertTrue(getSettings.contains("Holder.value:()Ljava/lang/Object;"),
                "getSettings is the official Holder.value owner");
        assertTrue(place.contains("getSettings:"),
                "place must call getSettings");
    }
}
