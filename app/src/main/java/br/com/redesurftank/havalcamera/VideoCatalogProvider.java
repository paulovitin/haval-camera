package br.com.redesurftank.havalcamera;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

/** Signature-readable completed metadata and MP4 content. Mutations use bounded provider calls. */
public final class VideoCatalogProvider extends ContentProvider {
    public static final String AUTHORITY = "br.com.redesurftank.havalcamera.video";
    private static final int TRIPS = 1, SEGMENT = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "trips", TRIPS);
        MATCHER.addURI(AUTHORITY, "trips/#/segments/#", SEGMENT);
    }
    private VideoCatalog catalog;
    private VideoSettings settings;
    private CommandJournal journal;

    @Override public boolean onCreate() {
        try {
            catalog = VideoCatalog.shared(getContext().getFilesDir());
            settings = VideoSettings.from(getContext());
            journal = new CommandJournal(getContext().getSharedPreferences("trip-video-journal", 0));
            return true;
        } catch (Exception failure) { return false; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (MATCHER.match(uri) != TRIPS) throw new IllegalArgumentException("Unknown read-only URI");
        MatrixCursor cursor = new MatrixCursor(new String[]{"trip_id","source","start_ms","end_ms","byte_size","segments"});
        for (VideoCatalog.Trip trip : catalog.completedSnapshot()) {
            long bytes = 0; for (VideoCatalog.Segment segment : trip.segments) bytes += segment.byteSize;
            cursor.addRow(new Object[]{trip.tripId, trip.source.name().toLowerCase(), trip.startMs,
                    trip.endMs, bytes, trip.segments.size()});
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || MATCHER.match(uri) != SEGMENT) throw new FileNotFoundException("Read only");
        try {
            File file = catalog.completedSegmentFile(uri.getPathSegments().get(1), uri.getLastPathSegment());
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception failure) { throw new FileNotFoundException("Completed segment unavailable"); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("getSettings".equals(method)) return settingsBundle(settings.snapshot(), journal.settingsRevision(), true);
        if ("updateSettings".equals(method)) {
            if (extras == null) throw new IllegalArgumentException("Missing settings patch");
            long expected = extras.getLong(VideoContract.EXTRA_EXPECTED_SETTINGS_REVISION, -1);
            String requestId = extras.getString(VideoContract.EXTRA_REQUEST_ID);
            Map<String, Object> patch = new HashMap<>();
            Bundle values = extras.getBundle(VideoContract.EXTRA_SETTINGS_PATCH);
            if (values == null || values.size() > 16) throw new IllegalArgumentException("Invalid settings patch");
            for (String key : values.keySet()) patch.put(key, values.get(key));
            VideoSettings.Snapshot current = settings.snapshot();
            CommandJournal.SettingsDecision<VideoSettings.Snapshot> decision = journal.applySettings(
                    expected, requestId, current, () -> settings.applyPatch(patch));
            Intent refresh = new Intent(getContext(), TripVideoService.class)
                    .setAction(VideoContract.ACTION_REFRESH_SETTINGS);
            if (android.os.Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(refresh);
            else getContext().startService(refresh);
            return settingsBundle(decision.snapshot, decision.revision, decision.applied);
        }
        throw new IllegalArgumentException("Unsupported provider call");
    }

    static Bundle settingsBundle(VideoSettings.Snapshot value, long revision, boolean applied) {
        Bundle result = new Bundle();
        result.putLong(VideoContract.EXTRA_SETTINGS_REVISION, revision);
        result.putBoolean("applied", applied);
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("automaticRecording", value.automaticRecording);
        snapshot.putBoolean("recordParkedLive", value.recordParkedLive);
        snapshot.putBoolean("remoteLive", value.remoteLive);
        snapshot.putBoolean("remoteDownloads", value.remoteDownloads);
        snapshot.putString("quality", value.quality.name().toLowerCase());
        snapshot.putInt("reserveGb", (int) (value.reserveBytes >> 30));
        snapshot.putBoolean("automaticDeletion", value.automaticDeletion);
        snapshot.putBoolean("overlayMaster", value.overlayMaster);
        snapshot.putBoolean("overlayDateTime", value.overlayDateTime);
        snapshot.putBoolean("overlaySpeed", value.overlaySpeed);
        snapshot.putBoolean("overlayAcceleration", value.overlayAcceleration);
        snapshot.putBoolean("overlayDriveState", value.overlayDriveState);
        snapshot.putBoolean("overlayDecelerationRegen", value.overlayDecelerationRegen);
        snapshot.putString("overlayPosition", value.overlayPosition.name().toLowerCase());
        result.putBundle(VideoContract.EXTRA_SETTINGS_SNAPSHOT, snapshot);
        return result;
    }

    @Override public String getType(Uri uri) {
        return MATCHER.match(uri) == TRIPS ? "vnd.android.cursor.dir/vnd.havalcamera.trip" : "video/mp4";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
}
