package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clear and place sequences, exercised against a fake level that recreates
 * a block entity on an IMMEDIATE read. No Minecraft level is started.
 */
class BlockEntityRestoreTest {

    @Test
    void failedAirWriteDoesNotDiscardContents() {
        Level level = new Level();
        level.air = false;
        level.ownsEntity = true;
        level.entityPresent = true;
        level.contents = 5;
        level.airWritesThatFail = 1;

        boolean ok = BlockEntityRestore.clearToAir(level.clearOps());

        assertThat(ok).isFalse();
        assertThat(level.air).isFalse();
        assertThat(level.entityPresent).isTrue();
        assertThat(level.contents).isEqualTo(5);
        assertThat(level.dropped).isZero();
        assertThat(level.discarded).isZero();
        assertThat(level.removeCount).isZero();
        assertThat(level.immediateReads).isZero();
    }

    @Test
    void successfulClearDropsContentsThroughTheAirWrite() {
        Level level = new Level();
        level.air = false;
        level.ownsEntity = true;
        level.entityPresent = true;
        level.contents = 4;

        boolean ok = BlockEntityRestore.clearToAir(level.clearOps());

        assertThat(ok).isTrue();
        assertThat(level.air).isTrue();
        assertThat(level.entityPresent).isFalse();
        assertThat(level.dropped).isEqualTo(4);
        assertThat(level.contents).isZero();
        assertThat(level.discarded).isZero();
        assertThat(level.removeCount).isZero();
        assertThat(level.immediateReads).isZero();
    }

    @Test
    void staleEntityOnNonEntityBlockIsRemovedAfterAir() {
        Level level = new Level();
        level.air = false;
        level.ownsEntity = false;
        level.entityPresent = true;

        boolean ok = BlockEntityRestore.clearToAir(level.clearOps());

        assertThat(ok).isTrue();
        assertThat(level.air).isTrue();
        assertThat(level.entityPresent).isFalse();
        assertThat(level.removeCount).isEqualTo(1);
        assertThat(level.discarded).isZero();
        assertThat(level.immediateReads).isZero();
    }

    @Test
    void survivorThatResistsRemovalFailsTheClear() {
        Level level = new Level();
        level.air = false;
        level.ownsEntity = false;
        level.entityPresent = true;
        level.removalFails = true;

        boolean ok = BlockEntityRestore.clearToAir(level.clearOps());

        assertThat(ok).isFalse();
        assertThat(level.entityPresent).isTrue();
        assertThat(level.removeCount).isEqualTo(1);
        assertThat(level.immediateReads).isZero();
    }

    @Test
    void immediateReadAfterPrematureRemoveRecreatesAnEmptyEntity() {
        Level level = new Level();
        level.air = false;
        level.ownsEntity = true;
        level.entityPresent = true;
        level.contents = 3;
        level.removeBlockEntity();
        level.immediateRead();

        assertThat(level.discarded).isEqualTo(3);
        assertThat(level.entityPresent).isTrue();
        assertThat(level.contents).isZero();
        assertThat(level.immediateReads).isEqualTo(1);
    }

    @Test
    void placeClearsStaleEntityWhenTargetOwnsNone() {
        Level level = new Level();
        level.entityPresent = true;
        level.placeOwnsEntity = false;

        boolean ok = BlockEntityRestore.place(level.placeOps(false));

        assertThat(ok).isTrue();
        assertThat(level.entityPresent).isFalse();
        assertThat(level.removeCount).isEqualTo(1);
        assertThat(level.restores).isZero();
        assertThat(level.immediateReads).isZero();
    }

    @Test
    void placeFailsAndRestoresWhenStaleEntitySurvives() {
        Level level = new Level();
        level.entityPresent = true;
        level.placeOwnsEntity = false;
        level.removalFails = true;

        boolean ok = BlockEntityRestore.place(level.placeOps(false));

        assertThat(ok).isFalse();
        assertThat(level.staleClearFailures).isEqualTo(1);
        assertThat(level.restores).isEqualTo(1);
        assertThat(level.entityPresent).isTrue();
    }

    @Test
    void placeFailsAndRestoresWhenEntityOwningTargetHasNoEntityAfterTheWrite() {
        Level level = new Level();
        level.placeOwnsEntity = true;
        level.entityPresent = false;
        level.skipEntityCreation = true;

        boolean ok = BlockEntityRestore.place(level.placeOps(false));

        assertThat(ok).isFalse();
        assertThat(level.restores).isEqualTo(1);
        assertThat(level.nbtApplied).isFalse();
    }

    @Test
    void placeFailsAndRestoresWhenEntityOwningTargetHasNoEntityForTheNbtPath() {
        Level level = new Level();
        level.placeOwnsEntity = true;
        level.entityPresent = false;
        level.skipEntityCreation = true;

        boolean ok = BlockEntityRestore.place(level.placeOps(true));

        assertThat(ok).isFalse();
        assertThat(level.restores).isEqualTo(1);
        assertThat(level.nbtApplied).isFalse();
    }

    @Test
    void placeKeepsFreshEntityWhenTargetOwnsOneAndNoNbtWasSupplied() {
        Level level = new Level();
        level.placeOwnsEntity = true;
        level.entityPresent = false;

        boolean ok = BlockEntityRestore.place(level.placeOps(false));

        assertThat(ok).isTrue();
        assertThat(level.entityPresent).isTrue();
        assertThat(level.removeCount).isZero();
        assertThat(level.nbtApplied).isFalse();
        assertThat(level.restores).isZero();
    }

    @Test
    void placeAppliesNbtWithoutTreatingTheFreshEntityAsStale() {
        Level level = new Level();
        level.placeOwnsEntity = true;

        boolean ok = BlockEntityRestore.place(level.placeOps(true));

        assertThat(ok).isTrue();
        assertThat(level.nbtApplied).isTrue();
        assertThat(level.removeCount).isZero();
        assertThat(level.restores).isZero();
        assertThat(level.immediateReads).isZero();
    }

    @Test
    void placeFailsWhenNbtCannotBeApplied() {
        Level level = new Level();
        level.placeOwnsEntity = true;
        level.applyFails = true;

        boolean ok = BlockEntityRestore.place(level.placeOps(true));

        assertThat(ok).isFalse();
        assertThat(level.restores).isEqualTo(1);
        assertThat(level.nbtApplied).isFalse();
        assertThat(level.removeCount).isZero();
    }

    @Test
    void placeFailsWhenStateWriteDoesNotLand() {
        Level level = new Level();
        level.stateWriteFails = true;

        boolean ok = BlockEntityRestore.place(level.placeOps(false));

        assertThat(ok).isFalse();
        assertThat(level.restores).isEqualTo(1);
        assertThat(level.removeCount).isZero();
    }

    /**
     * Models vanilla: setAir on an entity-owning block drops contents and
     * removes the entity. setAir on anything else leaves a stale entity.
     * {@link #immediateRead()} recreates an empty entity when the state still
     * owns one, which is the server-thread IMMEDIATE trap.
     */
    private static final class Level {
        boolean air;
        boolean ownsEntity;
        boolean entityPresent;
        int contents;
        int dropped;
        int discarded;
        int removeCount;
        int immediateReads;
        int airWritesThatFail;
        boolean removalFails;
        boolean placeOwnsEntity;
        boolean stateWriteFails;
        boolean skipEntityCreation;
        boolean applyFails;
        boolean nbtApplied;
        int restores;
        int staleClearFailures;
        boolean stateMatches = true;

        BlockEntityRestore.ClearOps clearOps() {
            return new BlockEntityRestore.ClearOps() {
                @Override public void setAir() { Level.this.setAir(); }
                @Override public boolean isAir() { return air; }
                @Override public boolean blockEntityPresent() { return entityPresent; }
                @Override public void removeBlockEntity() { Level.this.removeBlockEntity(); }
            };
        }

        BlockEntityRestore.PlaceOps placeOps(boolean hasNbt) {
            return new BlockEntityRestore.PlaceOps() {
                @Override public void setState() { Level.this.setState(); }
                @Override public boolean stateMatches() { return stateMatches; }
                @Override public boolean targetOwnsBlockEntity() { return placeOwnsEntity; }
                @Override public boolean blockEntityPresent() { return entityPresent; }
                @Override public void removeBlockEntity() { Level.this.removeBlockEntity(); }
                @Override public boolean hasNbt() { return hasNbt; }
                @Override public boolean applyNbt() {
                    if (applyFails || !entityPresent) return false;
                    nbtApplied = true;
                    return true;
                }
                @Override public void restorePrevious() { restores++; }
                @Override public void onStaleClearFailed() { staleClearFailures++; }
            };
        }

        void setAir() {
            if (airWritesThatFail > 0) {
                airWritesThatFail--;
                return;
            }
            air = true;
            if (ownsEntity && entityPresent) {
                dropped += contents;
                contents = 0;
                entityPresent = false;
            }
        }

        void setState() {
            if (stateWriteFails) {
                stateMatches = false;
                return;
            }
            stateMatches = true;
            if (placeOwnsEntity && !skipEntityCreation) {
                entityPresent = true;
            }
        }

        void removeBlockEntity() {
            removeCount++;
            if (!air && contents > 0) {
                discarded += contents;
                contents = 0;
            }
            if (!removalFails) {
                entityPresent = false;
            }
        }

        void immediateRead() {
            immediateReads++;
            if (!air && ownsEntity) {
                if (contents > 0) {
                    discarded += contents;
                    contents = 0;
                }
                entityPresent = true;
            }
        }
    }
}
