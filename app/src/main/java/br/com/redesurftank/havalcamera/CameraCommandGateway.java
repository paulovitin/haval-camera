package br.com.redesurftank.havalcamera;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Synchronous provider facade over the foreground command service. */
final class CameraCommandGateway {
    private static final long TIMEOUT_MS = 1_500;
    private static final ConcurrentHashMap<Key, CompletableFuture<Bundle>> PENDING =
            new ConcurrentHashMap<>();

    static Bundle dispatch(Context context, String action, Bundle extras) {
        if (!VideoContract.isExternalCommandAction(action) || extras == null) {
            throw new IllegalArgumentException("Invalid camera command");
        }
        if (VideoContract.ACTION_TELEMETRY_SAMPLE.equals(action)) {
            start(context, action, extras);
            Bundle accepted = new Bundle();
            accepted.putString(VideoContract.EXTRA_COMMAND_STATUS, VideoContract.STATUS_APPLIED);
            return accepted;
        }
        String requestId = extras.getString(VideoContract.EXTRA_REQUEST_ID);
        long revision = extras.getLong(VideoContract.EXTRA_STATE_REVISION, -1);
        if (requestId == null || requestId.isEmpty() || revision < 0) {
            throw new IllegalArgumentException("Invalid command identity");
        }
        Key key = new Key(requestId, revision);
        CompletableFuture<Bundle> result = new CompletableFuture<>();
        if (PENDING.putIfAbsent(key, result) != null) {
            throw new IllegalStateException("Command already pending");
        }
        try {
            start(context, action, extras);
            return result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Camera command unavailable", failure);
        } finally {
            PENDING.remove(key, result);
        }
    }

    private static void start(Context context, String action, Bundle extras) {
        Intent command = new Intent(context, TripVideoService.class)
                .setAction(action).putExtras(new Bundle(extras));
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(command);
        else context.startService(command);
    }

    static void complete(String requestId, long requestRevision, long cameraRevision,
            String status, String error) {
        CompletableFuture<Bundle> pending = PENDING.get(new Key(requestId, requestRevision));
        if (pending == null) return;
        Bundle result = new Bundle();
        result.putLong(VideoContract.EXTRA_CAMERA_REVISION, cameraRevision);
        result.putString(VideoContract.EXTRA_COMMAND_STATUS, status);
        if (error != null) result.putString(VideoContract.EXTRA_ERROR_MESSAGE, error);
        pending.complete(result);
    }

    private static final class Key {
        final String requestId;
        final long revision;
        Key(String requestId, long revision) { this.requestId = requestId; this.revision = revision; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return revision == key.revision && requestId.equals(key.requestId);
        }
        @Override public int hashCode() { return Objects.hash(requestId, revision); }
    }

    private CameraCommandGateway() { }
}
