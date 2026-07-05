package network.vonix.guardian.core.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W3 — CI shim for {@link BenchExplosionAffectedListJoin}.
 *
 * <p>Locks the ≥ 85 % server-thread wall-time reduction target so that a
 * regression in the join path fails the build. The async path now snapshots
 * scratch arrays for correctness before handing string joins to the worker, so
 * the old ≥90 % target was too tight and flaky for the corrected boundary.</p>
 */
class BenchExplosionAffectedListJoinTest {

    @Test
    void serverThreadWallTime_reducedByAtLeast85Percent() throws Exception {
        BenchExplosionAffectedListJoin.Result r = BenchExplosionAffectedListJoin.run();
        // The old path does correctness-equivalent chunked StringBuilder joins
        // + submits on the caller. The new path snapshots scratch arrays on the
        // caller, then hands the string joins to the worker.
        assertThat(r.reductionPercent()).isGreaterThan(85.0);
    }
}
