package br.com.redesurftank.havalcamera;

import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Durable replay ordering for cross-application commands. */
public final class CommandJournal {
    private static final String STATE_REVISION = "command.stateRevision";
    private static final String HAS_STATE_REVISION = "command.hasStateRevision";
    private static final String RECONCILE_REVISION = "command.reconcileRevision";
    private static final String ACTIVE_TRIP_ID = "command.activeTripId";
    private static final String LAST_TRIP_ID = "command.lastTripId";
    private static final String REQUEST_IDS = "command.requestIds";
    private static final String SETTINGS_REVISION = "settings.revision";
    private static final String SETTINGS_REQUEST_IDS = "settings.requestIds";

    private final SharedPreferences preferences;
    private long revision;
    private boolean hasRevision;
    private long reconcileRevision;
    private String activeTripId;
    private String lastTripId;
    // shortcut: request IDs live for the install lifetime; compact only after defining a safe replay window.
    private final Set<String> requestIds;
    private long settingsRevision;
    private final Set<String> settingsRequestIds;

    public CommandJournal(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        revision = preferences.getLong(STATE_REVISION, 0);
        hasRevision = preferences.getBoolean(HAS_STATE_REVISION, false);
        reconcileRevision = preferences.getLong(RECONCILE_REVISION, -1);
        activeTripId = preferences.getString(ACTIVE_TRIP_ID, null);
        lastTripId = preferences.getString(LAST_TRIP_ID, null);
        requestIds = copy(preferences.getStringSet(REQUEST_IDS, null));
        settingsRevision = preferences.getLong(SETTINGS_REVISION, 0);
        settingsRequestIds = copy(preferences.getStringSet(SETTINGS_REQUEST_IDS, null));
    }

    public synchronized Decision apply(
            long incomingRevision, String requestId, VideoContract.Command command, String tripId) {
        Objects.requireNonNull(command, "command");
        if (!command.journaled) return decision(Outcome.EPHEMERAL, null);
        validateRevision(incomingRevision);
        validateRequestId(requestId);
        validateTrip(command, tripId);

        if (requestIds.contains(requestId)) return decision(Outcome.DUPLICATE, null);
        if ((hasRevision && incomingRevision < revision)
                || (command != VideoContract.Command.RECONCILE
                    && incomingRevision <= reconcileRevision)
                || (hasRevision && incomingRevision == revision
                    && command != VideoContract.Command.RECONCILE)) {
            return decision(Outcome.STALE_REVISION, null);
        }

        String previousTripId = activeTripId;
        Outcome outcome = Outcome.APPLIED;
        switch (command) {
            case START_TRIP:
                if (tripId.equals(activeTripId)) outcome = Outcome.NO_OP;
                else {
                    activeTripId = tripId;
                    lastTripId = tripId;
                }
                break;
            case STOP_TRIP:
            case DISCARD_TRIP:
                if (activeTripId == null && tripId.equals(lastTripId)) outcome = Outcome.NO_OP;
                else if (!tripId.equals(activeTripId)) return decision(Outcome.STALE_TRIP, null);
                else activeTripId = null;
                break;
            case RECONCILE:
                if (Objects.equals(activeTripId, tripId)) outcome = Outcome.NO_OP;
                activeTripId = tripId;
                if (tripId != null) lastTripId = tripId;
                reconcileRevision = incomingRevision;
                break;
            default:
                break;
        }

        revision = incomingRevision;
        hasRevision = true;
        requestIds.add(requestId);
        persistCommands();
        return decision(outcome, previousTripId);
    }

    public synchronized <T> SettingsDecision<T> applySettings(
            long expectedRevision, String requestId, T currentSnapshot, Supplier<T> mutation) {
        validateRevision(expectedRevision);
        validateRequestId(requestId);
        Objects.requireNonNull(mutation, "mutation");
        if (settingsRequestIds.contains(requestId) || expectedRevision != settingsRevision) {
            return new SettingsDecision<>(false, settingsRevision, currentSnapshot);
        }

        T updatedSnapshot = mutation.get();
        settingsRevision++;
        settingsRequestIds.add(requestId);
        if (!preferences.edit()
                .putLong(SETTINGS_REVISION, settingsRevision)
                .putStringSet(SETTINGS_REQUEST_IDS, new HashSet<>(settingsRequestIds))
                .commit()) {
            throw new IllegalStateException("Could not persist settings revision");
        }
        return new SettingsDecision<>(true, settingsRevision, updatedSnapshot);
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized long settingsRevision() {
        return settingsRevision;
    }

    public synchronized String activeTripId() {
        return activeTripId;
    }

    private Decision decision(Outcome outcome, String previousTripId) {
        return new Decision(outcome, revision, activeTripId, previousTripId);
    }

    private void persistCommands() {
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(STATE_REVISION, revision)
                .putBoolean(HAS_STATE_REVISION, hasRevision)
                .putLong(RECONCILE_REVISION, reconcileRevision)
                .putStringSet(REQUEST_IDS, new HashSet<>(requestIds));
        if (activeTripId == null) editor.remove(ACTIVE_TRIP_ID);
        else editor.putString(ACTIVE_TRIP_ID, activeTripId);
        if (lastTripId == null) editor.remove(LAST_TRIP_ID);
        else editor.putString(LAST_TRIP_ID, lastTripId);
        if (!editor.commit()) throw new IllegalStateException("Could not persist command journal");
    }

    private static void validateRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("Revision must be non-negative");
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128) {
            throw new IllegalArgumentException("Request ID must contain 1 to 128 characters");
        }
    }

    private static void validateTrip(VideoContract.Command command, String tripId) {
        if (command == VideoContract.Command.RECONCILE) return;
        if ((command == VideoContract.Command.START_TRIP
                || command == VideoContract.Command.STOP_TRIP
                || command == VideoContract.Command.DISCARD_TRIP)
                && (tripId == null || tripId.isEmpty())) {
            throw new IllegalArgumentException("Trip ID is required for " + command);
        }
    }

    private static Set<String> copy(Set<String> values) {
        return values == null ? new HashSet<>() : new HashSet<>(values);
    }

    public enum Outcome {
        APPLIED,
        NO_OP,
        DUPLICATE,
        STALE_REVISION,
        STALE_TRIP,
        EPHEMERAL
    }

    public static final class Decision {
        public final Outcome outcome;
        public final long revision;
        public final String activeTripId;
        public final String previousTripId;

        private Decision(Outcome outcome, long revision, String activeTripId, String previousTripId) {
            this.outcome = outcome;
            this.revision = revision;
            this.activeTripId = activeTripId;
            this.previousTripId = previousTripId;
        }
    }

    public static final class SettingsDecision<T> {
        public final boolean applied;
        public final long revision;
        public final T snapshot;

        private SettingsDecision(boolean applied, long revision, T snapshot) {
            this.applied = applied;
            this.revision = revision;
            this.snapshot = snapshot;
        }
    }
}
