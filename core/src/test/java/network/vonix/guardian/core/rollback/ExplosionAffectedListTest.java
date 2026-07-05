package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExplosionAffectedList}.
 *
 * <p>W5 (v1.3.0) — cover parse/serialize round-trips, malformed-entry
 * tolerance, and the {@code anyWithinRadius} bounding-box check that
 * {@link RollbackEngine} uses to admit explosions whose center is outside a
 * caller's radius but whose damage reaches into it.</p>
 */
class ExplosionAffectedListTest {

    @Test
    void parseHandlesEmptyAndNull() {
        assertThat(ExplosionAffectedList.parse(null).isEmpty()).isTrue();
        assertThat(ExplosionAffectedList.parse("").isEmpty()).isTrue();
        assertThat(ExplosionAffectedList.parse("   ").isEmpty()).isTrue();
    }

    @Test
    void parseSingleEntry() {
        ExplosionAffectedList list = ExplosionAffectedList.parse("100:64:0=minecraft:oak_planks");
        assertThat(list.size()).isEqualTo(1);
        ExplosionAffectedList.Entry e = list.entries().get(0);
        assertThat(e.x()).isEqualTo(100);
        assertThat(e.y()).isEqualTo(64);
        assertThat(e.z()).isEqualTo(0);
        assertThat(e.blockId()).isEqualTo("minecraft:oak_planks");
        assertThat(e.meta()).isNull();
    }

    @Test
    void parseMultipleEntries() {
        ExplosionAffectedList list = ExplosionAffectedList.parse(
            "100:64:0=minecraft:stone,101:64:0=minecraft:dirt,102:64:0=minecraft:air");
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.entries()).extracting(ExplosionAffectedList.Entry::blockId)
            .containsExactly("minecraft:stone", "minecraft:dirt", "minecraft:air");
    }

    @Test
    void parseEntryWithMetaAfterPipe() {
        // Meta cannot contain top-level commas (they're the entry separator);
        // event producers strip commas before writing.
        ExplosionAffectedList list = ExplosionAffectedList.parse(
            "5:64:5=minecraft:oak_stairs|facing=north");
        assertThat(list.size()).isEqualTo(1);
        ExplosionAffectedList.Entry e = list.entries().get(0);
        assertThat(e.blockId()).isEqualTo("minecraft:oak_stairs");
        assertThat(e.meta()).isEqualTo("facing=north");
    }

    @Test
    void parseSkipsMalformedEntriesButKeepsGoodOnes() {
        // bad: missing '=', bad: non-numeric coord, bad: only x:y, bad: empty block id
        ExplosionAffectedList list = ExplosionAffectedList.parse(
            "1:2:3=minecraft:stone," +
            "malformed_no_equals," +
            "a:b:c=minecraft:dirt," +
            "4:5=minecraft:cobble," +
            "6:7:8=," +
            "9:10:11=minecraft:diamond_ore");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.entries()).extracting(ExplosionAffectedList.Entry::blockId)
            .containsExactly("minecraft:stone", "minecraft:diamond_ore");
    }

    @Test
    void parseHandlesNegativeCoords() {
        ExplosionAffectedList list = ExplosionAffectedList.parse("-100:-5:-30=minecraft:bedrock");
        assertThat(list.size()).isEqualTo(1);
        ExplosionAffectedList.Entry e = list.entries().get(0);
        assertThat(e.x()).isEqualTo(-100);
        assertThat(e.y()).isEqualTo(-5);
        assertThat(e.z()).isEqualTo(-30);
    }

    @Test
    void serializeRoundTripPreservesEntries() {
        String original = "100:64:0=minecraft:stone,101:65:0=minecraft:dirt,102:66:0=minecraft:air";
        ExplosionAffectedList list = ExplosionAffectedList.parse(original);
        String serialized = list.serialize();
        // Re-parse and compare.
        ExplosionAffectedList reparsed = ExplosionAffectedList.parse(serialized);
        assertThat(reparsed.size()).isEqualTo(list.size());
        for (int i = 0; i < list.size(); i++) {
            assertThat(reparsed.entries().get(i)).isEqualTo(list.entries().get(i));
        }
    }

    @Test
    void sidecarRoundTripEnrichesTargetEntriesWithoutInflatingTargetColumn() {
        byte[] be = new byte[]{1, 2, 3, 4};
        ExplosionAffectedList.Entry enriched = new ExplosionAffectedList.Entry(
            100, 64, 0, "minecraft:chest", "facing=north,waterlogged=false", be);

        byte[] sidecar = ExplosionAffectedList.serializeSidecar(List.of(enriched));
        ExplosionAffectedList parsed = ExplosionAffectedList.parse(
            "100:64:0=minecraft:chest,101:64:0=minecraft:stone", sidecar);

        assertThat(parsed.entries()).hasSize(2);
        ExplosionAffectedList.Entry chest = parsed.entries().get(0);
        assertThat(chest.blockId()).isEqualTo("minecraft:chest");
        assertThat(chest.meta()).isEqualTo("facing=north,waterlogged=false");
        assertThat(chest.blockEntityNbt()).containsExactly(be);
        assertThat(parsed.entries().get(1).meta()).isNull();
        assertThat(parsed.entries().get(1).blockEntityNbt()).isNull();
    }

    @Test
    void serializeEmpty() {
        assertThat(ExplosionAffectedList.parse("").serialize()).isEmpty();
        assertThat(ExplosionAffectedList.parse(null).serialize()).isEmpty();
    }

    @Test
    void anyWithinRadius_hitsBlockInsideBox() {
        ExplosionAffectedList list = ExplosionAffectedList.parse(
            "100:64:0=minecraft:stone,105:64:0=minecraft:dirt,110:64:0=minecraft:cobblestone");
        // Center at (108,64,0) with r=5 — (105,64,0) is in range, (100,64,0) is not.
        assertThat(list.anyWithinRadius(108, 64, 0, 5)).isTrue();
    }

    @Test
    void anyWithinRadius_missesWhenAllOutside() {
        ExplosionAffectedList list = ExplosionAffectedList.parse(
            "100:64:0=minecraft:stone,105:64:0=minecraft:dirt,110:64:0=minecraft:cobblestone");
        // Center far away
        assertThat(list.anyWithinRadius(500, 64, 0, 5)).isFalse();
    }

    @Test
    void anyWithinRadius_yAxisChecked() {
        ExplosionAffectedList list = ExplosionAffectedList.parse("100:100:0=minecraft:stone");
        // Y=64, r=5: block at y=100 is outside the box
        assertThat(list.anyWithinRadius(100, 64, 0, 5)).isFalse();
        assertThat(list.anyWithinRadius(100, 100, 0, 5)).isTrue();
    }

    @Test
    void anyWithinRadius_nullYSkipsYCheck() {
        ExplosionAffectedList list = ExplosionAffectedList.parse("100:200:0=minecraft:stone");
        // Y not constrained
        assertThat(list.anyWithinRadius(100, null, 0, 5)).isTrue();
    }

    @Test
    void anyWithinRadius_globalRadiusAcceptsEverything() {
        ExplosionAffectedList list = ExplosionAffectedList.parse("100:64:0=minecraft:stone");
        // radius -1 => #global
        assertThat(list.anyWithinRadius(0, 0, 0, -1)).isTrue();
    }

    @Test
    void anyWithinRadius_emptyListAlwaysFalse() {
        ExplosionAffectedList empty = ExplosionAffectedList.parse("");
        assertThat(empty.anyWithinRadius(0, 0, 0, 100)).isFalse();
        assertThat(empty.anyWithinRadius(0, null, 0, 100)).isFalse();
    }

    @Test
    void anyWithinRadius_inclusiveBoundary() {
        ExplosionAffectedList list = ExplosionAffectedList.parse("105:64:0=minecraft:stone");
        // Player at (100,64,0), r=5 — block at x=105 is exactly on the boundary (inclusive).
        assertThat(list.anyWithinRadius(100, 64, 0, 5)).isTrue();
        assertThat(list.anyWithinRadius(100, 64, 0, 4)).isFalse();
    }
}
