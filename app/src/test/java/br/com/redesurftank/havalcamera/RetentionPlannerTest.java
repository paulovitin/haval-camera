package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class RetentionPlannerTest {
    private static final long GB = 1_000_000_000L;

    @Test
    public void selectedOneFiveTenAndTwentyGbReservesAreAppliedExactly() {
        assertReserve(1 * GB);
        assertReserve(5 * GB);
        assertReserve(10 * GB);
        assertReserve(20 * GB);
    }

    @Test
    public void oldestCompletedWholeTripsAreDeletedUntilReserveIsRecovered() {
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Arrays.asList(
                        entry(30, 3_000, 4 * GB, true),
                        entry(10, 1_000, 2 * GB, true),
                        entry(20, 2_000, 3 * GB, true)),
                1 * GB, 6 * GB, true, null, Collections.emptyList());

        assertEquals(Arrays.asList(10L, 20L), plan.tripIds);
        assertTrue(plan.recordingMayContinue);
        assertFalse(plan.cannotRecoverReserve);
    }

    @Test
    public void automaticDeletionDisabledPreservesEveryTripAndStopsRecording() {
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Collections.singletonList(entry(10, 1_000, 10 * GB, true)),
                1 * GB, 5 * GB, false, null, Collections.emptyList());

        assertTrue(plan.tripIds.isEmpty());
        assertFalse(plan.recordingMayContinue);
        assertTrue(plan.cannotRecoverReserve);
    }

    @Test
    public void activeAndTransferringTripsAreLocked() {
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Arrays.asList(
                        entry(10, 1_000, 4 * GB, true),
                        entry(20, 2_000, 4 * GB, true),
                        entry(30, 3_000, 4 * GB, true)),
                1 * GB, 5 * GB, true, 10L, Collections.singletonList(20L));

        assertEquals(Collections.singletonList(30L), plan.tripIds);
        assertTrue(plan.recordingMayContinue);
    }

    @Test
    public void activeIncompleteTripIsNeverADeletionCandidate() {
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Arrays.asList(
                        entry(10, 1_000, 10 * GB, false),
                        entry(20, 2_000, 4 * GB, true)),
                1 * GB, 5 * GB, true, 10L, Collections.emptyList());

        assertEquals(Collections.singletonList(20L), plan.tripIds);
        assertTrue(plan.recordingMayContinue);
    }

    @Test
    public void unrelatedDataThatMakesReserveImpossibleDoesNotDestroyRecordings() {
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Collections.singletonList(entry(10, 1_000, 2 * GB, true)),
                1 * GB, 5 * GB, true, null, Collections.emptyList());

        assertTrue(plan.tripIds.isEmpty());
        assertFalse(plan.recordingMayContinue);
        assertTrue(plan.cannotRecoverReserve);
    }

    @Test
    public void oneDeletionAlwaysRepresentsAWholeTrip() {
        RetentionPlanner.Entry trip = new RetentionPlanner.Entry(10, 1_000,
                Arrays.asList(2 * GB, 3 * GB), true);
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Collections.singletonList(trip), 1 * GB, 5 * GB,
                true, null, Collections.emptyList());

        assertEquals(Collections.singletonList(10L), plan.tripIds);
        assertEquals(5 * GB, trip.byteSize);
        assertTrue(plan.recordingMayContinue);
    }

    private static void assertReserve(long reserveBytes) {
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(
                Collections.singletonList(entry(10, 1_000, 2 * GB, true)),
                reserveBytes - 1, reserveBytes, true, null, Collections.emptyList());
        assertEquals(Collections.singletonList(10L), plan.tripIds);
        assertTrue(plan.recordingMayContinue);
    }

    private static RetentionPlanner.Entry entry(
            long tripId, long startedAtMs, long byteSize, boolean completed) {
        return new RetentionPlanner.Entry(
                tripId, startedAtMs, Collections.singletonList(byteSize), completed);
    }
}
