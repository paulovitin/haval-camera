package br.com.redesurftank.havalcamera;

/** Stable same-device IPC names shared with the separately signed EcoTrip application. */
public final class VideoContract {
    public static final int PROTOCOL_VERSION = 1;
    public static final String CONTROL_PERMISSION =
            "br.com.redesurftank.havalcamera.permission.CONTROL";
    public static final String READ_PERMISSION =
            "br.com.redesurftank.havalcamera.permission.READ_VIDEO";

    private static final String ACTION_PREFIX = "br.com.redesurftank.havalcamera.action.";
    private static final String EXTRA_PREFIX = "br.com.redesurftank.havalcamera.extra.";

    public static final String ACTION_START_TRIP = ACTION_PREFIX + "START_TRIP";
    public static final String ACTION_STOP_TRIP = ACTION_PREFIX + "STOP_TRIP";
    public static final String ACTION_DISCARD_TRIP = ACTION_PREFIX + "DISCARD_TRIP";
    public static final String ACTION_RECONCILE = ACTION_PREFIX + "RECONCILE";
    public static final String ACTION_START_LIVE = ACTION_PREFIX + "START_LIVE";
    public static final String ACTION_STOP_LIVE = ACTION_PREFIX + "STOP_LIVE";
    public static final String ACTION_TRANSFER = ACTION_PREFIX + "TRANSFER";
    public static final String ACTION_CANCEL_TRANSFER = ACTION_PREFIX + "CANCEL_TRANSFER";
    public static final String ACTION_TELEMETRY_SAMPLE = ACTION_PREFIX + "TELEMETRY_SAMPLE";
    public static final String ACTION_GET_SETTINGS = ACTION_PREFIX + "GET_SETTINGS";
    public static final String ACTION_UPDATE_SETTINGS = ACTION_PREFIX + "UPDATE_SETTINGS";
    public static final String ACTION_DELETE_COMPLETED = ACTION_PREFIX + "DELETE_COMPLETED";
    public static final String ACTION_COMMAND_RESULT = ACTION_PREFIX + "COMMAND_RESULT";

    // Same-package UI contracts are intentionally not part of the exported EcoTrip API.
    static final String ACTION_START_MANUAL_RECORDING = ACTION_PREFIX + "START_MANUAL_RECORDING";
    static final String ACTION_STOP_MANUAL_RECORDING = ACTION_PREFIX + "STOP_MANUAL_RECORDING";
    static final String ACTION_REFRESH_SETTINGS = ACTION_PREFIX + "REFRESH_SETTINGS";

    public static final String EXTRA_PROTOCOL_VERSION = EXTRA_PREFIX + "PROTOCOL_VERSION";
    public static final String EXTRA_REQUEST_ID = EXTRA_PREFIX + "REQUEST_ID";
    public static final String EXTRA_STATE_REVISION = EXTRA_PREFIX + "STATE_REVISION";
    public static final String EXTRA_TRIP_ID = EXTRA_PREFIX + "TRIP_ID";
    public static final String EXTRA_START_MS = EXTRA_PREFIX + "START_MS";
    public static final String EXTRA_END_MS = EXTRA_PREFIX + "END_MS";
    public static final String EXTRA_SESSION_URL = EXTRA_PREFIX + "SESSION_URL";
    public static final String EXTRA_SESSION_ID = EXTRA_PREFIX + "SESSION_ID";
    public static final String EXTRA_PRODUCER_TOKEN = EXTRA_PREFIX + "PRODUCER_TOKEN";
    public static final String EXTRA_EXPIRES_AT_MS = EXTRA_PREFIX + "EXPIRES_AT_MS";
    public static final String EXTRA_SEGMENT_IDS = EXTRA_PREFIX + "SEGMENT_IDS";
    public static final String EXTRA_VIEWER_SESSION = EXTRA_PREFIX + "VIEWER_SESSION";
    public static final String EXTRA_MONOTONIC_MS = EXTRA_PREFIX + "MONOTONIC_MS";
    public static final String EXTRA_WALL_CLOCK_MS = EXTRA_PREFIX + "WALL_CLOCK_MS";
    public static final String EXTRA_SPEED_KMH = EXTRA_PREFIX + "SPEED_KMH";
    public static final String EXTRA_MOTOR_POWER_KW = EXTRA_PREFIX + "MOTOR_POWER_KW";
    public static final String EXTRA_VALIDITY_FLAGS = EXTRA_PREFIX + "VALIDITY_FLAGS";
    public static final String EXTRA_EXPECTED_SETTINGS_REVISION =
            EXTRA_PREFIX + "EXPECTED_SETTINGS_REVISION";
    public static final String EXTRA_SETTINGS_REVISION = EXTRA_PREFIX + "SETTINGS_REVISION";
    public static final String EXTRA_SETTINGS_PATCH = EXTRA_PREFIX + "SETTINGS_PATCH";
    public static final String EXTRA_SETTINGS_SNAPSHOT = EXTRA_PREFIX + "SETTINGS_SNAPSHOT";
    public static final String EXTRA_DELETE_CONFIRMED = EXTRA_PREFIX + "DELETE_CONFIRMED";
    public static final String EXTRA_COMMAND_STATUS = EXTRA_PREFIX + "COMMAND_STATUS";
    public static final String EXTRA_CAMERA_REVISION = EXTRA_PREFIX + "CAMERA_REVISION";
    public static final String EXTRA_ERROR_MESSAGE = EXTRA_PREFIX + "ERROR_MESSAGE";

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_NO_OP = "NO_OP";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_ERROR = "ERROR";

    public static final int TELEMETRY_VALID_SPEED = 1;
    public static final int TELEMETRY_VALID_MOTOR_POWER = 1 << 1;
    public static final long TELEMETRY_MAX_AGE_MS = 2_000;

    private VideoContract() { }

    public enum Command {
        START_TRIP(true),
        STOP_TRIP(true),
        DISCARD_TRIP(true),
        RECONCILE(true),
        START_LIVE(true),
        STOP_LIVE(true),
        TRANSFER(true),
        CANCEL_TRANSFER(true),
        DELETE_COMPLETED(true),
        START_MANUAL_RECORDING(false),
        STOP_MANUAL_RECORDING(false),
        TELEMETRY_SAMPLE(false),
        GET_SETTINGS(false),
        UPDATE_SETTINGS(false);

        final boolean journaled;

        Command(boolean journaled) {
            this.journaled = journaled;
        }
    }

    public static boolean isFreshTelemetrySample(long sampleMonotonicMs, long nowMonotonicMs) {
        long age = nowMonotonicMs - sampleMonotonicMs;
        return age >= 0 && age <= TELEMETRY_MAX_AGE_MS;
    }

    public static void requireConfirmedDelete(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("Completed-recording deletion is not confirmed");
    }

    public static boolean isExternalCommandAction(String action) {
        return ACTION_START_TRIP.equals(action) || ACTION_STOP_TRIP.equals(action)
                || ACTION_DISCARD_TRIP.equals(action) || ACTION_RECONCILE.equals(action)
                || ACTION_START_LIVE.equals(action) || ACTION_STOP_LIVE.equals(action)
                || ACTION_TRANSFER.equals(action) || ACTION_CANCEL_TRANSFER.equals(action)
                || ACTION_TELEMETRY_SAMPLE.equals(action) || ACTION_DELETE_COMPLETED.equals(action);
    }
}
