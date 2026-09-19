package network.vonix.threadedhorizons.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LithiumFamilyDetectionTest {

    @Test
    void lithiumOrCanaryIsTheSameAllocFamily() {
        assertTrue(LoaderHooks.isLithiumFamilyId("lithium"));
        assertTrue(LoaderHooks.isLithiumFamilyId("canary"));
        assertFalse(LoaderHooks.isLithiumFamilyId("modernfix"));
        assertFalse(LoaderHooks.isLithiumFamilyId(""));
        assertFalse(LoaderHooks.isLithiumFamilyId(null));
    }
}
