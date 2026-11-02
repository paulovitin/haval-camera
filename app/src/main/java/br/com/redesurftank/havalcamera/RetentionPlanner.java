package br.com.redesurftank.havalcamera;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure whole-trip retention planning. Files are deleted by the caller. */
public final class RetentionPlanner {
    private RetentionPlanner() { }

    public static Plan selectDeletions(
            List<Entry> entries,
            long freeBytes,
            long reserveBytes,
            boolean automaticDeletion,
            Long activeTripId,
            Collection<Long> transferringTripIds) {
        if (entries == null || transferringTripIds == null) throw new NullPointerException();
        if (freeBytes < 0 || reserveBytes < 0) {
            throw new IllegalArgumentException("Storage byte counts must be non-negative");
        }
        if (freeBytes >= reserveBytes) return new Plan(Collections.emptyList(), true, false);
        if (!automaticDeletion) return new Plan(Collections.emptyList(), false, true);

        Set<Long> locked = new HashSet<>(transferringTripIds);
        if (activeTripId != null) locked.add(activeTripId);
        List<Entry> candidates = new ArrayList<>();
        long recoverableBytes = freeBytes;
        for (Entry entry : entries) {
            if (entry.completed && !locked.contains(entry.tripId)) {
                candidates.add(entry);
                recoverableBytes = saturatedAdd(recoverableBytes, entry.byteSize);
            }
        }

        // If deletion cannot make recording safe, preserve the footage and stop instead.
        if (recoverableBytes < reserveBytes) {
            return new Plan(Collections.emptyList(), false, true);
        }

        candidates.sort(Comparator.comparingLong((Entry entry) -> entry.startedAtMs)
                .thenComparingLong(entry -> entry.tripId));
        List<Long> deletions = new ArrayList<>();
        long plannedFreeBytes = freeBytes;
        for (Entry candidate : candidates) {
            deletions.add(candidate.tripId);
            plannedFreeBytes = saturatedAdd(plannedFreeBytes, candidate.byteSize);
            if (plannedFreeBytes >= reserveBytes) break;
        }
        return new Plan(deletions, true, false);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    public static final class Entry {
        public final long tripId;
        public final long startedAtMs;
        public final long byteSize;
        public final boolean completed;

        public Entry(long tripId, long startedAtMs, List<Long> segmentByteSizes, boolean completed) {
            if (tripId < 0 || startedAtMs < 0 || segmentByteSizes == null) {
                throw new IllegalArgumentException("Invalid retention entry");
            }
            long total = 0;
            for (Long segmentBytes : segmentByteSizes) {
                if (segmentBytes == null || segmentBytes < 0) {
                    throw new IllegalArgumentException("Invalid segment byte size");
                }
                total = Math.addExact(total, segmentBytes);
            }
            this.tripId = tripId;
            this.startedAtMs = startedAtMs;
            this.byteSize = total;
            this.completed = completed;
        }
    }

    public static final class Plan {
        public final List<Long> tripIds;
        public final boolean recordingMayContinue;
        public final boolean cannotRecoverReserve;

        private Plan(List<Long> tripIds, boolean recordingMayContinue, boolean cannotRecoverReserve) {
            this.tripIds = Collections.unmodifiableList(new ArrayList<>(tripIds));
            this.recordingMayContinue = recordingMayContinue;
            this.cannotRecoverReserve = cannotRecoverReserve;
        }
    }
}
