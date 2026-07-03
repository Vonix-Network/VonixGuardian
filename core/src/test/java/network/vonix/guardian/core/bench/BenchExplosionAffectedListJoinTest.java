package network.vonix.guardian.core.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W3 — CI shim for {@link BenchExplosionAffectedListJoin}.
 *
 * <p>Locks the ≥ 90 % server-thread wall-time reduction target so that a
 * regression in the join path fails the build.</p>
 */
class BenchExplosionAffectedListJoinTest {

    @Test
    void serverThreadWallTime_reducedByAtLeast90Percent() throws Exception {
        BenchExplosionAffectedListJoin.Result r = BenchExplosionAffectedListJoin.run();
        // The old path does 5,000 StringBuilder.append cycles + a submit on the
        // caller. The new path does only the submit() call itself (which just
        // hands to a worker via ExecutorService.execute) — no join work.
        assertThat(r.reductionPercent()).isGreaterThan(90.0);
    }
}
