package network.vonix.threadedhorizons.common.notickvd;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import network.vonix.threadedhorizons.common.util.FilteringIterable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoTickSystemTest {

    @Test
    void entityFilterSkipsNoTickOnlyPositions() {
        List<Long> entities = List.of(1L, 2L, 3L, 4L);
        LongOpenHashSet noTickOnly = new LongOpenHashSet();
        noTickOnly.add(2L);
        noTickOnly.add(4L);
        FilteringIterable<Long> filtered = new FilteringIterable<>(entities, pos -> !noTickOnly.contains(pos.longValue()));
        List<Long> seen = new ArrayList<>();
        for (Long pos : filtered) {
            seen.add(pos);
        }
        assertEquals(List.of(1L, 3L), seen);
    }

    @Test
    void filterIteratorIsExhaustive() {
        FilteringIterable<Integer> filtered = new FilteringIterable<>(List.of(1, 2, 3), value -> value != 2);
        Iterator<Integer> iterator = filtered.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(1, iterator.next());
        assertEquals(3, iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }
}
