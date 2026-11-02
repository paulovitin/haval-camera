package br.com.redesurftank.havalcamera;

import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
        return applyStaged(incomingRevision, requestId, command, tripId, editor -> { });
    }

    public synchronized Decision applyStaged(long incomingRevision, String requestId,
            VideoContract.Command command, String tripId, Consumer<SharedPreferences.Editor> staging) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(staging, "staging");
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
        long nextReconcileRevision = reconcileRevision;
        String nextActiveTripId = activeTripId;
        String nextLastTripId = lastTripId;
        Outcome outcome = Outcome.APPLIED;
        switch (command) {
            case START_TRIP:
                if (tripId.equals(activeTripId)) outcome = Outcome.NO_OP;
                else {
                    nextActiveTripId = tripId;
                    nextLastTripId = tripId;
                }
                break;
            case STOP_TRIP:
            case DISCARD_TRIP:
                if (activeTripId == null && tripId.equals(lastTripId)) outcome = Outcome.NO_OP;
                else if (!tripId.equals(activeTripId)) return decision(Outcome.STALE_TRIP, null);
                else nextActiveTripId = null;
                break;
            case RECONCILE:
                if (Objects.equals(activeTripId, tripId)) outcome = Outcome.NO_OP;
                nextActiveTripId = tripId;
                if (tripId != null) nextLastTripId = tripId;
                nextReconcileRevision = incomingRevision;
                break;
            default:
                break;
        }

        Set<String> nextRequestIds = new HashSet<>(requestIds);
        nextRequestIds.add(requestId);
        persistCommands(incomingRevision, nextReconcileRevision,
                nextActiveTripId, nextLastTripId, nextRequestIds, staging);
        revision = incomingRevision;
        hasRevision = true;
        reconcileRevision = nextReconcileRevision;
        activeTripId = nextActiveTripId;
        lastTripId = nextLastTripId;
        requestIds.add(requestId);
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
        long nextSettingsRevision = settingsRevision + 1;
        Set<String> nextSettingsRequestIds = new HashSet<>(settingsRequestIds);
        nextSettingsRequestIds.add(requestId);
        if (!preferences.edit()
                .putLong(SETTINGS_REVISION, nextSettingsRevision)
                .putStringSet(SETTINGS_REQUEST_IDS, nextSettingsRequestIds)
                .commit()) {
            throw new IllegalStateException("Could not persist settings revision");
        }
        settingsRevision = nextSettingsRevision;
        settingsRequestIds.add(requestId);
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

    private void persistCommands(long nextRevision, long nextReconcileRevision,
            String nextActiveTripId, String nextLastTripId, Set<String> nextRequestIds,
            Consumer<SharedPreferences.Editor> staging) {
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(STATE_REVISION, nextRevision)
                .putBoolean(HAS_STATE_REVISION, true)
                .putLong(RECONCILE_REVISION, nextReconcileRevision)
                .putStringSet(REQUEST_IDS, nextRequestIds);
        if (nextActiveTripId == null) editor.remove(ACTIVE_TRIP_ID);
        else editor.putString(ACTIVE_TRIP_ID, nextActiveTripId);
        if (nextLastTripId == null) editor.remove(LAST_TRIP_ID);
        else editor.putString(LAST_TRIP_ID, nextLastTripId);
        staging.accept(editor);
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
