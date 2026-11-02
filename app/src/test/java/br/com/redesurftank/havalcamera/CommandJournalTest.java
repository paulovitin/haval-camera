package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class CommandJournalTest {
    @Test
    public void duplicateRequestIdsAreIgnoredAcrossRestart() {
        MemoryPreferences preferences = new MemoryPreferences();
        CommandJournal first = new CommandJournal(preferences);

        assertEquals(CommandJournal.Outcome.APPLIED,
                first.apply(1, "request-1", VideoContract.Command.START_TRIP, "100").outcome);

        CommandJournal restarted = new CommandJournal(preferences);
        CommandJournal.Decision duplicate =
                restarted.apply(2, "request-1", VideoContract.Command.STOP_TRIP, "100");

        assertEquals(CommandJournal.Outcome.DUPLICATE, duplicate.outcome);
        assertEquals("100", duplicate.activeTripId);
        assertEquals(1, duplicate.revision);
    }

    @Test
    public void lowerRevisionIsIgnoredAcrossRestart() {
        MemoryPreferences preferences = new MemoryPreferences();
        new CommandJournal(preferences)
                .apply(8, "request-8", VideoContract.Command.START_TRIP, "800");

        CommandJournal.Decision stale = new CommandJournal(preferences)
                .apply(7, "request-7", VideoContract.Command.STOP_TRIP, "800");

        assertEquals(CommandJournal.Outcome.STALE_REVISION, stale.outcome);
        assertEquals("800", stale.activeTripId);
        assertEquals(8, stale.revision);
    }

    @Test
    public void staleStopAndDiscardCannotAffectANewerTrip() {
        CommandJournal journal = new CommandJournal(new MemoryPreferences());
        journal.apply(10, "start-new", VideoContract.Command.START_TRIP, "200");

        CommandJournal.Decision stop =
                journal.apply(11, "stop-old", VideoContract.Command.STOP_TRIP, "100");
        CommandJournal.Decision discard =
                journal.apply(12, "discard-old", VideoContract.Command.DISCARD_TRIP, "100");

        assertEquals(CommandJournal.Outcome.STALE_TRIP, stop.outcome);
        assertEquals(CommandJournal.Outcome.STALE_TRIP, discard.outcome);
        assertEquals("200", discard.activeTripId);
        assertEquals(10, discard.revision);
    }

    @Test
    public void reconcileSupersedesIncrementalCommandsAtSameOrLowerRevision() {
        CommandJournal journal = new CommandJournal(new MemoryPreferences());
        assertEquals(CommandJournal.Outcome.APPLIED,
                journal.apply(20, "start", VideoContract.Command.START_TRIP, "100").outcome);
        assertEquals(CommandJournal.Outcome.APPLIED,
                journal.apply(20, "reconcile", VideoContract.Command.RECONCILE, "200").outcome);

        CommandJournal.Decision same =
                journal.apply(20, "same", VideoContract.Command.STOP_TRIP, "200");
        CommandJournal.Decision lower =
                journal.apply(19, "lower", VideoContract.Command.DISCARD_TRIP, "200");

        assertEquals(CommandJournal.Outcome.STALE_REVISION, same.outcome);
        assertEquals(CommandJournal.Outcome.STALE_REVISION, lower.outcome);
        assertEquals("200", lower.activeTripId);
    }

    @Test
    public void staleSettingsRevisionReturnsCurrentSnapshotWithoutMutation() {
        CommandJournal journal = new CommandJournal(new MemoryPreferences());
        AtomicBoolean mutated = new AtomicBoolean();
        CommandJournal.SettingsDecision<String> first =
                journal.applySettings(0, "settings-1", "standard", () -> "high");

        CommandJournal.SettingsDecision<String> stale =
                journal.applySettings(0, "settings-2", first.snapshot, () -> {
                    mutated.set(true);
                    return "economy";
                });

        assertTrue(first.applied);
        assertEquals(1, first.revision);
        assertEquals("high", first.snapshot);
        assertFalse(stale.applied);
        assertEquals(1, stale.revision);
        assertEquals("high", stale.snapshot);
        assertFalse(mutated.get());
        assertEquals(0, journal.revision());
    }

    @Test
    public void failedCommandCommitLeavesJournalRetryable() {
        MemoryPreferences preferences = new MemoryPreferences();
        CommandJournal journal = new CommandJournal(preferences);
        preferences.failNextCommit();

        assertThrows(IllegalStateException.class,
                () -> journal.apply(1, "request-1", VideoContract.Command.START_TRIP, "100"));
        assertEquals(0, journal.revision());
        assertNull(journal.activeTripId());

        CommandJournal.Decision retry =
                journal.apply(1, "request-1", VideoContract.Command.START_TRIP, "100");
        assertEquals(CommandJournal.Outcome.APPLIED, retry.outcome);
        assertEquals(1, retry.revision);
        assertEquals("100", retry.activeTripId);
    }

    @Test
    public void failedSettingsCommitLeavesJournalRetryable() {
        MemoryPreferences preferences = new MemoryPreferences();
        CommandJournal journal = new CommandJournal(preferences);
        preferences.failNextCommit();

        assertThrows(IllegalStateException.class,
                () -> journal.applySettings(0, "settings-1", "standard", () -> "high"));
        assertEquals(0, journal.settingsRevision());

        CommandJournal.SettingsDecision<String> retry =
                journal.applySettings(0, "settings-1", "standard", () -> "high");
        assertTrue(retry.applied);
        assertEquals(1, retry.revision);
        assertEquals("high", retry.snapshot);
    }

    @Test public void failedStartMetadataStagingDoesNotConsumeCommand() {
        MemoryPreferences preferences = new MemoryPreferences();
        CommandJournal journal = new CommandJournal(preferences);
        preferences.failNextCommit();
        assertThrows(IllegalStateException.class, () -> journal.applyStaged(
                1, "start-1", VideoContract.Command.START_TRIP, "10",
                editor -> editor.putLong("trip.start.10", 123)));
        assertEquals(0, journal.revision());
        assertEquals(-1, preferences.getLong("trip.start.10", -1));
        assertEquals(CommandJournal.Outcome.APPLIED, journal.applyStaged(
                1, "start-1", VideoContract.Command.START_TRIP, "10",
                editor -> editor.putLong("trip.start.10", 123)).outcome);
        assertEquals(123, preferences.getLong("trip.start.10", -1));
    }

    @Test
    public void telemetryIsFreshnessCheckedAndNeverJournaled() {
        CommandJournal journal = new CommandJournal(new MemoryPreferences());

        assertTrue(VideoContract.isFreshTelemetrySample(9_000, 10_000));
        assertFalse(VideoContract.isFreshTelemetrySample(7_999, 10_000));
        assertFalse(VideoContract.isFreshTelemetrySample(10_001, 10_000));
        assertEquals(CommandJournal.Outcome.EPHEMERAL,
                journal.apply(50, "telemetry", VideoContract.Command.TELEMETRY_SAMPLE, null).outcome);
        assertEquals(0, journal.revision());
        assertNull(journal.activeTripId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void deleteCompletedRequiresExplicitConfirmation() {
        VideoContract.requireConfirmedDelete(false);
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        private boolean failNextCommit;

        void failNextCommit() { failNextCommit = true; }

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : fallback;
        }
        @Override public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }
        @Override public long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }
        @Override public float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }
        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) { updates.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) {
                updates.put(key, new HashSet<>(value)); return this;
            }
            @Override public Editor putInt(String key, int value) { updates.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { updates.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { updates.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { updates.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                if (failNextCommit) { failNextCommit = false; return false; }
                apply();
                return true;
            }
            @Override public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(updates);
            }
        }
    }
}
