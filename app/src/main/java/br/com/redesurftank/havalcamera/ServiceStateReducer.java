package br.com.redesurftank.havalcamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure lifecycle reducer. Android callbacks only execute returned effects. */
public final class ServiceStateReducer {
    static final long VIEWER_GRACE_MS = 15_000;
    static final long PARKED_LIVE_LIMIT_MS = 30 * 60_000;

    public enum Mode { IDLE, RECORDING, LIVE_ONLY, RECORDING_LIVE }
    public enum EffectType {
        START_RECORDING, STOP_RECORDING, START_CAPTURE, STOP_CAPTURE,
        ATTACH_LIVE, DETACH_LIVE, PAUSE_TRANSFER, RESUME_TRANSFER, CANCEL_TRANSFER
    }

    public static final class Effect {
        public final EffectType type;
        public final String tripId;
        Effect(EffectType type, String tripId) { this.type = type; this.tripId = tripId; }
    }

    public static final class State {
        public final Mode mode;
        public final String tripId;
        public final boolean manualRecording;
        public final boolean transferActive;
        public final boolean transferPaused;
        public final boolean automaticRecording;
        public final boolean recordParkedLive;
        public final boolean remoteLive;
        public final boolean remoteDownloads;
        final String authoritativeTripId;
        final long viewerLostAtMs;
        final long liveStartedAtMs;

        private State(Mode mode, String tripId, boolean manualRecording,
                boolean transferActive, boolean transferPaused, boolean automaticRecording,
                boolean recordParkedLive, boolean remoteLive, boolean remoteDownloads,
                String authoritativeTripId, long viewerLostAtMs, long liveStartedAtMs) {
            this.mode = mode;
            this.tripId = tripId;
            this.manualRecording = manualRecording;
            this.transferActive = transferActive;
            this.transferPaused = transferPaused;
            this.automaticRecording = automaticRecording;
            this.recordParkedLive = recordParkedLive;
            this.remoteLive = remoteLive;
            this.remoteDownloads = remoteDownloads;
            this.authoritativeTripId = authoritativeTripId;
            this.viewerLostAtMs = viewerLostAtMs;
            this.liveStartedAtMs = liveStartedAtMs;
        }

        public static State idle() {
            return new State(Mode.IDLE, null, false, false, false,
                    true, false, true, true, null, -1, -1);
        }

        public static State recovered(String tripId, boolean manual) {
            return new State(Mode.RECORDING, requiredId(tripId), manual, false, false,
                    true, false, true, true, tripId, -1, -1);
        }
    }

    public enum EventType {
        START_AUTOMATIC, START_MANUAL, STOP_RECORDING, VIEWER_CONNECTED,
        VIEWER_LOST, TRANSFER_STARTED, TRANSFER_STOPPED, SETTINGS, TICK
    }

    public static final class Event {
        final EventType type;
        final long sequence;
        final String tripId;
        final long nowMs;
        final boolean automaticRecording;
        final boolean recordParkedLive;
        final boolean remoteLive;
        final boolean remoteDownloads;

        private Event(EventType type, long sequence, String tripId, long nowMs,
                boolean automaticRecording, boolean recordParkedLive,
                boolean remoteLive, boolean remoteDownloads) {
            this.type = type; this.sequence = sequence; this.tripId = tripId; this.nowMs = nowMs;
            this.automaticRecording = automaticRecording;
            this.recordParkedLive = recordParkedLive;
            this.remoteLive = remoteLive;
            this.remoteDownloads = remoteDownloads;
        }

        public static Event startAutomatic(String id, long sequence) {
            return simple(EventType.START_AUTOMATIC, sequence, requiredId(id), 0);
        }
        public static Event startManual(String id, long sequence) {
            return simple(EventType.START_MANUAL, sequence, requiredId(id), 0);
        }
        public static Event stopRecording(long sequence) {
            return simple(EventType.STOP_RECORDING, sequence, null, 0);
        }
        public static Event viewerConnected(long sequence, long nowMs) {
            return simple(EventType.VIEWER_CONNECTED, sequence, null, nowMs);
        }
        public static Event viewerLost(long sequence, long nowMs) {
            return simple(EventType.VIEWER_LOST, sequence, null, nowMs);
        }
        public static Event transferStarted(long sequence) {
            return simple(EventType.TRANSFER_STARTED, sequence, null, 0);
        }
        public static Event transferStopped(long sequence) {
            return simple(EventType.TRANSFER_STOPPED, sequence, null, 0);
        }
        public static Event tick(long sequence, long nowMs) {
            return simple(EventType.TICK, sequence, null, nowMs);
        }
        public static Event settings(long sequence, boolean automatic, boolean parked,
                boolean live, boolean downloads, String currentTripId) {
            return new Event(EventType.SETTINGS, sequence, currentTripId, 0,
                    automatic, parked, live, downloads);
        }
        private static Event simple(EventType type, long sequence, String id, long nowMs) {
            return new Event(type, sequence, id, nowMs, true, false, true, true);
        }
    }

    private State state;
    private long lastSequence = -1;

    public ServiceStateReducer() { this(State.idle()); }
    public ServiceStateReducer(State initial) { state = initial; }
    public State state() { return state; }

    public List<Effect> apply(Event event) {
        if (event.sequence <= lastSequence) return Collections.emptyList();
        lastSequence = event.sequence;
        State old = state;
        String recording = old.tripId;
        boolean manual = old.manualRecording;
        String authoritative = old.authoritativeTripId;
        boolean transfer = old.transferActive;
        boolean paused = old.transferPaused;
        boolean automatic = old.automaticRecording;
        boolean parked = old.recordParkedLive;
        boolean liveAllowed = old.remoteLive;
        boolean downloads = old.remoteDownloads;
        boolean viewer = old.mode == Mode.LIVE_ONLY || old.mode == Mode.RECORDING_LIVE;
        long lostAt = old.viewerLostAtMs;
        long liveStarted = old.liveStartedAtMs;

        switch (event.type) {
            case START_AUTOMATIC:
                authoritative = event.tripId;
                if (automatic) { recording = event.tripId; manual = false; }
                break;
            case START_MANUAL:
                recording = event.tripId; manual = true; break;
            case STOP_RECORDING:
                recording = null; manual = false; authoritative = null; break;
            case VIEWER_CONNECTED:
                if (liveAllowed) {
                    viewer = true; lostAt = -1; liveStarted = event.nowMs;
                    if (parked && recording == null) {
                        recording = Long.toString(event.nowMs);
                        manual = true;
                    }
                }
                break;
            case VIEWER_LOST:
                if (viewer) lostAt = event.nowMs;
                break;
            case TRANSFER_STARTED:
                if (downloads) transfer = true;
                break;
            case TRANSFER_STOPPED:
                transfer = false; paused = false; break;
            case SETTINGS:
                automatic = event.automaticRecording;
                parked = event.recordParkedLive;
                liveAllowed = event.remoteLive;
                downloads = event.remoteDownloads;
                authoritative = event.tripId;
                if (!automatic && !manual) recording = null;
                else if (automatic && recording == null && authoritative != null) recording = authoritative;
                if (!liveAllowed) { viewer = false; lostAt = -1; liveStarted = -1; }
                if (!downloads) { transfer = false; paused = false; }
                break;
            case TICK:
                if (lostAt >= 0 && event.nowMs - lostAt >= VIEWER_GRACE_MS) {
                    viewer = false; lostAt = -1; liveStarted = -1;
                } else if (recording == null && liveStarted >= 0
                        && event.nowMs - liveStarted >= PARKED_LIVE_LIMIT_MS) {
                    viewer = false; lostAt = -1; liveStarted = -1;
                }
                break;
        }
        paused = transfer && viewer;
        Mode mode = recording == null ? (viewer ? Mode.LIVE_ONLY : Mode.IDLE)
                : (viewer ? Mode.RECORDING_LIVE : Mode.RECORDING);
        state = new State(mode, recording, manual, transfer, paused, automatic, parked,
                liveAllowed, downloads, authoritative, lostAt, liveStarted);
        return effects(old, state);
    }

    private static List<Effect> effects(State old, State next) {
        List<Effect> result = new ArrayList<>();
        boolean oldRecording = old.tripId != null;
        boolean newRecording = next.tripId != null;
        boolean oldLive = old.mode == Mode.LIVE_ONLY || old.mode == Mode.RECORDING_LIVE;
        boolean newLive = next.mode == Mode.LIVE_ONLY || next.mode == Mode.RECORDING_LIVE;
        if (oldRecording && (!newRecording || !old.tripId.equals(next.tripId)))
            result.add(new Effect(EffectType.STOP_RECORDING, old.tripId));
        if ((!oldRecording && newRecording)
                || (oldRecording && newRecording && !old.tripId.equals(next.tripId)))
            result.add(new Effect(EffectType.START_RECORDING, next.tripId));
        if (!newRecording && newLive && (!oldLive || oldRecording))
            result.add(new Effect(EffectType.START_CAPTURE, null));
        if (!oldLive && newLive) result.add(new Effect(EffectType.ATTACH_LIVE, null));
        if (oldLive && !newLive) result.add(new Effect(EffectType.DETACH_LIVE, null));
        if ((oldRecording || oldLive) && !newRecording && !newLive)
            result.add(new Effect(EffectType.STOP_CAPTURE, null));
        if (!old.transferPaused && next.transferPaused) result.add(new Effect(EffectType.PAUSE_TRANSFER, null));
        if (old.transferPaused && next.transferActive && !next.transferPaused)
            result.add(new Effect(EffectType.RESUME_TRANSFER, null));
        if (old.transferActive && !next.transferActive)
            result.add(new Effect(EffectType.CANCEL_TRANSFER, null));
        return Collections.unmodifiableList(result);
    }

    private static String requiredId(String id) {
        if (id == null || !id.matches("[0-9]+")) throw new IllegalArgumentException("Numeric trip ID required");
        return id;
    }
}
