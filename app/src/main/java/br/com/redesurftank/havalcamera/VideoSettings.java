package br.com.redesurftank.havalcamera;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Validated private settings. One atomic store write publishes each snapshot. */
public final class VideoSettings {
    public enum Quality { ECONOMY, STANDARD, HIGH }
    public enum OverlayPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    interface Store { Map<String, Object> read(); boolean write(Map<String, Object> values); }

    public static final class MemoryStore implements Store {
        private Map<String, Object> values = new HashMap<>();
        private boolean failNext;
        @Override public synchronized Map<String, Object> read() { return new HashMap<>(values); }
        @Override public synchronized boolean write(Map<String, Object> next) {
            if (failNext) { failNext = false; return false; }
            values = new HashMap<>(next); return true;
        }
        public synchronized void failNextWrite() { failNext = true; }
    }

    private final Store store;
    private Snapshot snapshot;
    private long revision;
    private final Set<String> requestIds = new HashSet<>();
    private static final String REVISION = "_settingsRevision";
    private static final String REQUESTS = "_settingsRequestIds";

    public static VideoSettings from(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("video-settings", Context.MODE_PRIVATE);
        return new VideoSettings(new Store() {
            @Override public Map<String, Object> read() {
                return new HashMap<>(preferences.getAll());
            }
            @Override public boolean write(Map<String, Object> values) {
                SharedPreferences.Editor editor = preferences.edit().clear();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                    else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                    else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                    else editor.putString(entry.getKey(), value.toString());
                }
                return editor.commit();
            }
        });
    }

    VideoSettings(Store store) {
        this.store = Objects.requireNonNull(store, "store");
        Map<String, Object> values = store.read();
        snapshot = Snapshot.from(values);
        Object savedRevision = values.get(REVISION);
        revision = savedRevision instanceof Number ? ((Number) savedRevision).longValue() : 0;
        Object requests = values.get(REQUESTS);
        if (requests instanceof String && !((String) requests).isEmpty())
            Collections.addAll(requestIds, ((String) requests).split("\\n", -1));
    }

    public synchronized Snapshot snapshot() { return snapshot; }

    public synchronized Snapshot reload() {
        Map<String, Object> values = store.read();
        snapshot = Snapshot.from(values);
        Object savedRevision = values.get(REVISION);
        revision = savedRevision instanceof Number ? ((Number) savedRevision).longValue() : 0;
        return snapshot;
    }

    public synchronized long revision() { return revision; }

    public synchronized Update applyRevisionedPatch(long expectedRevision, String requestId,
            Map<String, ?> patch) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128
                || requestId.contains("\n")) throw new IllegalArgumentException("Invalid request ID");
        if (expectedRevision != revision || requestIds.contains(requestId))
            return new Update(false, revision, snapshot);
        Snapshot candidate = patched(patch);
        long nextRevision = revision + 1;
        Set<String> nextRequests = new HashSet<>(requestIds); nextRequests.add(requestId);
        Map<String, Object> values = candidate.values();
        values.put(REVISION, nextRevision);
        values.put(REQUESTS, String.join("\n", nextRequests));
        if (!store.write(values)) throw new IllegalStateException("Could not persist settings");
        snapshot = candidate; revision = nextRevision; requestIds.add(requestId);
        return new Update(true, revision, snapshot);
    }

    public synchronized Snapshot applyPatch(Map<String, ?> patch) {
        Snapshot candidate = patched(patch);
        Map<String, Object> values = candidate.values();
        values.put(REVISION, revision); values.put(REQUESTS, String.join("\n", requestIds));
        if (!store.write(values)) throw new IllegalStateException("Could not persist settings");
        snapshot = candidate;
        return candidate;
    }

    private Snapshot patched(Map<String, ?> patch) {
        if (patch == null || patch.isEmpty() || patch.size() > 16)
            throw new IllegalArgumentException("Settings patch is empty or too large");
        Map<String, Object> values = snapshot.values();
        for (Map.Entry<String, ?> entry : patch.entrySet()) {
            if (!values.containsKey(entry.getKey())) throw new IllegalArgumentException("Unknown setting");
            values.put(entry.getKey(), validate(entry.getKey(), entry.getValue()));
        }
        Snapshot candidate = Snapshot.from(values);
        return candidate;
    }

    public static final class Update {
        public final boolean applied; public final long revision; public final Snapshot snapshot;
        Update(boolean applied, long revision, Snapshot snapshot) {
            this.applied = applied; this.revision = revision; this.snapshot = snapshot;
        }
    }

    private static Object validate(String key, Object value) {
        switch (key) {
            case "automaticRecording": case "recordParkedLive": case "remoteLive":
            case "remoteDownloads": case "automaticDeletion": case "overlayMaster":
            case "overlayDateTime": case "overlaySpeed": case "overlayAcceleration":
            case "overlayDriveState": case "overlayDecelerationRegen":
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("Boolean setting required");
                return value;
            case "quality":
                return Quality.valueOf(requiredString(value).toUpperCase());
            case "reserveGb":
                int reserve = ((Number) value).intValue();
                if (reserve != 1 && reserve != 5 && reserve != 10 && reserve != 20)
                    throw new IllegalArgumentException("Unsupported reserve");
                return reserve;
            case "overlayPosition":
                return OverlayPosition.valueOf(requiredString(value).toUpperCase());
            default: throw new IllegalArgumentException("Unknown setting");
        }
    }

    private static String requiredString(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException("String setting required");
        return (String) value;
    }

    public static final class Snapshot {
        public final boolean automaticRecording, recordParkedLive, remoteLive, remoteDownloads;
        public final Quality quality;
        public final long reserveBytes;
        public final boolean automaticDeletion, overlayMaster, overlayDateTime, overlaySpeed;
        public final boolean overlayAcceleration, overlayDriveState, overlayDecelerationRegen;
        public final OverlayPosition overlayPosition;

        private Snapshot(Map<String, Object> values) {
            automaticRecording = bool(values, "automaticRecording", true);
            recordParkedLive = bool(values, "recordParkedLive", false);
            remoteLive = bool(values, "remoteLive", true);
            remoteDownloads = bool(values, "remoteDownloads", true);
            quality = enumValue(values, "quality", Quality.STANDARD, Quality.class);
            int reserveGb = number(values, "reserveGb", 1);
            if (reserveGb != 1 && reserveGb != 5 && reserveGb != 10 && reserveGb != 20)
                reserveGb = 1;
            reserveBytes = ((long) reserveGb) << 30;
            automaticDeletion = bool(values, "automaticDeletion", true);
            overlayMaster = bool(values, "overlayMaster", true);
            overlayDateTime = bool(values, "overlayDateTime", true);
            overlaySpeed = bool(values, "overlaySpeed", true);
            overlayAcceleration = bool(values, "overlayAcceleration", true);
            overlayDriveState = bool(values, "overlayDriveState", true);
            overlayDecelerationRegen = bool(values, "overlayDecelerationRegen", true);
            overlayPosition = enumValue(values, "overlayPosition", OverlayPosition.BOTTOM_LEFT,
                    OverlayPosition.class);
        }

        static Snapshot from(Map<String, ?> values) {
            return new Snapshot(values == null ? Collections.emptyMap() : new HashMap<>(values));
        }
        Map<String, Object> values() {
            Map<String, Object> values = new HashMap<>();
            values.put("automaticRecording", automaticRecording);
            values.put("recordParkedLive", recordParkedLive);
            values.put("remoteLive", remoteLive);
            values.put("remoteDownloads", remoteDownloads);
            values.put("quality", quality.name().toLowerCase());
            values.put("reserveGb", (int) (reserveBytes >> 30));
            values.put("automaticDeletion", automaticDeletion);
            values.put("overlayMaster", overlayMaster);
            values.put("overlayDateTime", overlayDateTime);
            values.put("overlaySpeed", overlaySpeed);
            values.put("overlayAcceleration", overlayAcceleration);
            values.put("overlayDriveState", overlayDriveState);
            values.put("overlayDecelerationRegen", overlayDecelerationRegen);
            values.put("overlayPosition", overlayPosition.name().toLowerCase());
            return values;
        }
        private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
            Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback;
        }
        private static int number(Map<String, Object> values, String key, int fallback) {
            Object value = values.get(key); return value instanceof Number ? ((Number) value).intValue() : fallback;
        }
        private static <T extends Enum<T>> T enumValue(Map<String, Object> values, String key,
                T fallback, Class<T> type) {
            Object value = values.get(key);
            if (value == null) return fallback;
            try { return Enum.valueOf(type, value.toString().toUpperCase()); }
            catch (IllegalArgumentException invalid) { return fallback; }
        }
        @Override public boolean equals(Object other) { return other instanceof Snapshot && values().equals(((Snapshot) other).values()); }
        @Override public int hashCode() { return values().hashCode(); }
    }
}
