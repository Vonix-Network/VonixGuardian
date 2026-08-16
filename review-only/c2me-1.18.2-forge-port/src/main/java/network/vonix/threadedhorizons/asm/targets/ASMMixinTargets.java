package network.vonix.threadedhorizons.asm.targets;

import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.levelgen.structure.MineShaftPieces;
import net.minecraft.world.level.levelgen.structure.NetherBridgePieces;
import net.minecraft.world.level.levelgen.structure.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import net.minecraft.world.level.levelgen.structure.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.SwamplandHutPiece;
import net.minecraft.world.level.levelgen.structure.WoodlandMansionPieces;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({
        MineShaftPieces.MineShaftCorridor.class,
        NetherBridgePieces.MonsterThrone.class,
        NetherBridgePieces.CastleSmallCorridorLeftTurnPiece.class,
        NetherBridgePieces.CastleSmallCorridorRightTurnPiece.class,
        NetherBridgePieces.StartPiece.class,
        OceanMonumentPieces.RoomDefinition.class,
        OceanMonumentPieces.MonumentBuilding.class,
        PoolElementStructurePiece.class,
        StrongholdPieces.ChestCorridor.class,
        StrongholdPieces.PortalRoom.class,
        StrongholdPieces.StartPiece.class,
        ScatteredFeaturePiece.class,
        SwamplandHutPiece.class,
        WoodlandMansionPieces.PlacementData.class,
        ServerChunkCache.class,
        NbtOps.NbtRecordBuilder.class
})
public class ASMMixinTargets {
}
