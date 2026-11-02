package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class TripTimestampsTest {
    @Test public void startMustBePresentAndWithinClockTolerance() {
        assertThrows(IllegalArgumentException.class,
                () -> TripTimestamps.requireStart(false, -1, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> TripTimestamps.requireStart(true, -1, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> TripTimestamps.requireStart(true, 310_001, 10_000));
        assertEquals(10_000, TripTimestamps.requireStart(true, 10_000, 10_000));
    }

    @Test public void endMustBePresentValidAndNotPrecedeStart() {
        assertThrows(IllegalArgumentException.class,
                () -> TripTimestamps.requireEnd(false, -1, 1_000, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> TripTimestamps.requireEnd(true, 999, 1_000, 10_000));
        assertEquals(2_000, TripTimestamps.requireEnd(true, 2_000, 1_000, 10_000));
    }
}
