package br.com.redesurftank.havalcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Foreground owner of recording lifecycle. Remote transport arrives in Task 8. */
public final class TripVideoService extends Service {
    private static final String CHANNEL = "trip-video";
    private static final int NOTIFICATION = 41;
    private static final String PREFERENCES = "trip-video-journal";
    private static final String ACTIVE_ID = "service.activeTripId";
    private static final String ACTIVE_MANUAL = "service.activeManual";
    private final ServiceStateReducer reducer = new ServiceStateReducer();
    private final TelemetryOverlayModel telemetry = new TelemetryOverlayModel();
    private VideoCatalog catalog;
    private VideoSettings settings;
    private CommandJournal journal;
    private CameraCaptureClient capture;
    private EglFrameEncoder encoder;
    private SegmentMuxer muxer;
    private RecordingCoordinator coordinator;
    private String activeTripId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { apply(ServiceStateReducer.Event.tick(nextSequence(), System.currentTimeMillis())); }
            catch (Exception failure) { fail(failure.getMessage()); }
            main.postDelayed(this, 1_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION, notification("Câmera pronta"));
        try {
            catalog = VideoCatalog.shared(getFilesDir());
            settings = VideoSettings.from(this);
            journal = new CommandJournal(getSharedPreferences(PREFERENCES, MODE_PRIVATE));
            capture = new CameraCaptureClient(this, (state, detail) -> {
                if (state == CameraCaptureClient.State.ERROR) fail(detail);
            });
            android.content.SharedPreferences serviceState = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
            boolean hasServiceRecording = serviceState.contains(ACTIVE_ID);
            String recovered = hasServiceRecording ? serviceState.getString(ACTIVE_ID, null)
                    : settings.snapshot().automaticRecording ? journal.activeTripId() : null;
            boolean recoveredManual = hasServiceRecording && serviceState.getBoolean(ACTIVE_MANUAL, false);
            if (recovered != null) apply(recoveredManual
                    ? ServiceStateReducer.Event.startManual(recovered, nextSequence())
                    : ServiceStateReducer.Event.startAutomatic(recovered, nextSequence()));
            main.postDelayed(tick, 1_000);
        } catch (Exception failure) {
            fail(failure.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null || journal == null) return START_STICKY;
        try { route(intent); }
        catch (Exception failure) { fail(failure.getMessage()); }
        return START_STICKY;
    }

    private void route(Intent intent) throws Exception {
        String action = intent.getAction();
        if (VideoContract.ACTION_REFRESH_SETTINGS.equals(action)) {
            VideoSettings.Snapshot value = settings.reload();
            apply(ServiceStateReducer.Event.settings(nextSequence(), value.automaticRecording,
                    value.recordParkedLive, value.remoteLive, value.remoteDownloads,
                    journal.activeTripId()));
            return;
        }
        if (VideoContract.ACTION_START_MANUAL_RECORDING.equals(action)) {
            long start = intent.getLongExtra(VideoContract.EXTRA_START_MS, System.currentTimeMillis());
            apply(ServiceStateReducer.Event.startManual(Long.toString(start), nextSequence()));
            return;
        }
        if (VideoContract.ACTION_STOP_MANUAL_RECORDING.equals(action)) {
            finishCurrent(intent.getLongExtra(VideoContract.EXTRA_END_MS, System.currentTimeMillis()), false);
            return;
        }
        if (VideoContract.ACTION_TELEMETRY_SAMPLE.equals(action)) {
            acceptTelemetry(intent); return;
        }
        VideoContract.Command command = command(action);
        if (command == null) return;
        int version = intent.getIntExtra(VideoContract.EXTRA_PROTOCOL_VERSION, -1);
        if (version != VideoContract.PROTOCOL_VERSION) throw new IllegalArgumentException("Unsupported protocol");
        String tripId = intent.getStringExtra(VideoContract.EXTRA_TRIP_ID);
        long revision = intent.getLongExtra(VideoContract.EXTRA_STATE_REVISION, -1);
        String requestId = intent.getStringExtra(VideoContract.EXTRA_REQUEST_ID);
        CommandJournal.Decision decision = journal.apply(revision, requestId, command, tripId);
        if (decision.outcome != CommandJournal.Outcome.APPLIED
                && decision.outcome != CommandJournal.Outcome.NO_OP) return;
        switch (command) {
            case START_TRIP:
            case RECONCILE:
                if (decision.previousTripId != null && !decision.previousTripId.equals(tripId))
                    finishTrip(decision.previousTripId, System.currentTimeMillis(), false);
                if (tripId == null) finishCurrent(System.currentTimeMillis(), false);
                else if (settings.snapshot().automaticRecording)
                    apply(ServiceStateReducer.Event.startAutomatic(tripId, nextSequence()));
                break;
            case STOP_TRIP:
                finishTrip(tripId, intent.getLongExtra(VideoContract.EXTRA_END_MS, System.currentTimeMillis()), false);
                break;
            case DISCARD_TRIP:
                finishTrip(tripId, System.currentTimeMillis(), true);
                break;
            case START_LIVE:
                apply(ServiceStateReducer.Event.viewerConnected(nextSequence(), System.currentTimeMillis()));
                break;
            case STOP_LIVE:
                apply(ServiceStateReducer.Event.viewerLost(nextSequence(), System.currentTimeMillis()));
                break;
            case TRANSFER:
                apply(ServiceStateReducer.Event.transferStarted(nextSequence())); break;
            case CANCEL_TRANSFER:
                apply(ServiceStateReducer.Event.transferStopped(nextSequence())); break;
            case DELETE_COMPLETED:
                VideoContract.requireConfirmedDelete(intent.getBooleanExtra(VideoContract.EXTRA_DELETE_CONFIRMED, false));
                deleteCompleted(); break;
            default: break;
        }
    }

    private long sequence;
    private long nextSequence() { return ++sequence; }

    private void apply(ServiceStateReducer.Event event) throws Exception {
        for (ServiceStateReducer.Effect effect : reducer.apply(event)) execute(effect);
        updateNotification();
    }

    private void execute(ServiceStateReducer.Effect effect) throws Exception {
        switch (effect.type) {
            case START_RECORDING: startRecording(effect.tripId, reducer.state().manualRecording); break;
            case STOP_RECORDING:
                stopPipeline();
                if (activeTripId != null && activeTripId.equals(effect.tripId)) {
                    catalog.completeTrip(activeTripId, System.currentTimeMillis());
                    activeTripId = null;
                    clearPersistedRecording();
                }
                break;
            case PAUSE_RECORDING:
                stopPipeline(); activeTripId = null; clearPersistedRecording(); break;
            case START_CAPTURE:
                if (effect.tripId == null && coordinator == null) startLiveOnly();
                break;
            case STOP_CAPTURE: stopPipeline(); break;
            case ATTACH_LIVE: case DETACH_LIVE:
                // Task 8 owns the bounded WebSocket sink. State still controls capture priority.
                break;
            default: break;
        }
    }

    private void startRecording(String tripId, boolean manual) throws Exception {
        if (tripId.equals(activeTripId) && coordinator != null) return;
        stopPipeline();
        try { catalog.beginTrip(tripId, Long.parseLong(tripId),
                manual ? VideoCatalog.Source.MANUAL : VideoCatalog.Source.AUTOMATIC); }
        catch (IllegalStateException existing) {
            boolean found = catalog.localSnapshot().stream().anyMatch(trip -> trip.tripId == Long.parseLong(tripId) && !trip.completed);
            if (!found) throw existing;
        }
        VideoSettings.Snapshot selected = settings.snapshot();
        int fps = selected.quality == VideoSettings.Quality.ECONOMY ? 10 : 15;
        int bitrate = selected.quality == VideoSettings.Quality.ECONOMY ? 2_000_000
                : selected.quality == VideoSettings.Quality.HIGH ? 6_000_000 : 4_000_000;
        muxer = new SegmentMuxer(getFilesDir(), tripId, catalog, this::pipelineFailure,
                fps, bitrate, this::enforceStorageReserve);
        final SegmentMuxer output = muxer;
        encoder = new EglFrameEncoder(new EglFrameEncoder.Listener() {
            @Override public void onOutputFormat(MediaFormat format) { try { output.setOutputFormat(format); } catch (Exception e) { pipelineFailure(e); } }
            @Override public void onCodecConfig(byte[] sps, byte[] pps) { }
            @Override public void onAccessUnit(EncodedAccessUnit unit) { if (coordinator != null) coordinator.onEncodedAccessUnit(unit); }
            @Override public void onFailure(Throwable failure) { if (coordinator != null) coordinator.onCodecFailure(failure); }
        }, fps, bitrate);
        coordinator = new RecordingCoordinator(output, capture::stop, encoder, this::pipelineFailure);
        activeTripId = tripId;
        if (!getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(ACTIVE_ID, tripId).putBoolean(ACTIVE_MANUAL, manual).commit()) {
            throw new IllegalStateException("Could not persist active recording");
        }
        encoder.start();
        long origin = System.nanoTime();
        capture.start(frame -> {
            EglFrameEncoder current = encoder;
            if (current != null) current.queueFrame(frame, (System.nanoTime() - origin) / 1_000);
        });
    }

    private void startLiveOnly() throws Exception {
        RecordingCoordinator.Sink discard = unit -> { };
        encoder = new EglFrameEncoder(new EglFrameEncoder.Listener() {
            @Override public void onOutputFormat(MediaFormat format) { }
            @Override public void onCodecConfig(byte[] sps, byte[] pps) { }
            @Override public void onAccessUnit(EncodedAccessUnit unit) { if (coordinator != null) coordinator.onEncodedAccessUnit(unit); }
            @Override public void onFailure(Throwable failure) { if (coordinator != null) coordinator.onCodecFailure(failure); }
        });
        coordinator = new RecordingCoordinator(discard, capture::stop, encoder, this::pipelineFailure);
        encoder.start();
        long origin = System.nanoTime();
        capture.start(frame -> { if (encoder != null) encoder.queueFrame(frame, (System.nanoTime()-origin)/1_000); });
    }

    private void finishCurrent(long endMs, boolean discard) throws Exception {
        if (activeTripId != null) finishTrip(activeTripId, endMs, discard);
    }

    private void finishTrip(String tripId, long endMs, boolean discard) throws Exception {
        if (tripId == null || !tripId.equals(activeTripId)) return;
        stopPipeline();
        if (discard) catalog.discardTrip(tripId); else catalog.completeTrip(tripId, endMs);
        activeTripId = null;
        clearPersistedRecording();
        apply(ServiceStateReducer.Event.stopRecording(nextSequence()));
    }

    private void stopPipeline() {
        capture.stop();
        EglFrameEncoder oldEncoder = encoder; encoder = null;
        if (oldEncoder != null) oldEncoder.close();
        RecordingCoordinator oldCoordinator = coordinator; coordinator = null;
        if (oldCoordinator != null) oldCoordinator.close();
        muxer = null;
    }

    private void clearPersistedRecording() {
        if (!getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .remove(ACTIVE_ID).remove(ACTIVE_MANUAL).commit()) {
            throw new IllegalStateException("Could not clear active recording");
        }
    }

    private void acceptTelemetry(Intent intent) {
        long now = SystemClock.elapsedRealtime();
        if (telemetry.acceptSample(
                intent.getLongExtra(VideoContract.EXTRA_MONOTONIC_MS, -1),
                intent.getLongExtra(VideoContract.EXTRA_WALL_CLOCK_MS, -1),
                intent.getFloatExtra(VideoContract.EXTRA_SPEED_KMH, Float.NaN),
                intent.getFloatExtra(VideoContract.EXTRA_MOTOR_POWER_KW, Float.NaN),
                intent.getIntExtra(VideoContract.EXTRA_VALIDITY_FLAGS, 0), now) && encoder != null) {
            VideoSettings.Snapshot value = settings.snapshot();
            encoder.setOverlaySnapshot(telemetry.snapshot(now, System.currentTimeMillis(),
                    new TelemetryOverlayModel.Settings(value.overlayMaster, value.overlayDateTime,
                            value.overlaySpeed, value.overlayAcceleration, value.overlayDriveState,
                            value.overlayDecelerationRegen,
                            TelemetryOverlayModel.Position.valueOf(value.overlayPosition.name()))));
        }
    }

    private void deleteCompleted() throws IOException {
        if (reducer.state().transferActive) throw new IOException("Transferência ativa");
        for (VideoCatalog.Trip trip : catalog.completedSnapshot()) catalog.discardTrip(Long.toString(trip.tripId));
    }

    private void enforceStorageReserve() throws IOException {
        VideoSettings.Snapshot value = settings.reload();
        List<RetentionPlanner.Entry> entries = new ArrayList<>();
        for (VideoCatalog.Trip trip : catalog.localSnapshot()) {
            List<Long> sizes = new ArrayList<>();
            for (VideoCatalog.Segment segment : trip.segments) sizes.add(segment.byteSize);
            entries.add(new RetentionPlanner.Entry(trip.tripId, trip.startMs, sizes, trip.completed));
        }
        RetentionPlanner.Plan plan = RetentionPlanner.selectDeletions(entries,
                getFilesDir().getUsableSpace(), value.reserveBytes, value.automaticDeletion,
                activeTripId == null ? null : Long.parseLong(activeTripId), Collections.emptyList());
        for (Long tripId : plan.tripIds) catalog.discardTrip(Long.toString(tripId));
        if (!plan.recordingMayContinue) throw new IOException("Reserva de armazenamento indisponível");
    }

    private static VideoContract.Command command(String action) {
        if (VideoContract.ACTION_START_TRIP.equals(action)) return VideoContract.Command.START_TRIP;
        if (VideoContract.ACTION_STOP_TRIP.equals(action)) return VideoContract.Command.STOP_TRIP;
        if (VideoContract.ACTION_DISCARD_TRIP.equals(action)) return VideoContract.Command.DISCARD_TRIP;
        if (VideoContract.ACTION_RECONCILE.equals(action)) return VideoContract.Command.RECONCILE;
        if (VideoContract.ACTION_START_LIVE.equals(action)) return VideoContract.Command.START_LIVE;
        if (VideoContract.ACTION_STOP_LIVE.equals(action)) return VideoContract.Command.STOP_LIVE;
        if (VideoContract.ACTION_TRANSFER.equals(action)) return VideoContract.Command.TRANSFER;
        if (VideoContract.ACTION_CANCEL_TRANSFER.equals(action)) return VideoContract.Command.CANCEL_TRANSFER;
        if (VideoContract.ACTION_DELETE_COMPLETED.equals(action)) return VideoContract.Command.DELETE_COMPLETED;
        return null;
    }

    private void pipelineFailure(Throwable failure) { fail(failure == null ? null : failure.getMessage()); }
    private void fail(String message) {
        Log.e("HavalCamera", "Video service: " + message);
        if (Build.VERSION.SDK_INT >= 26) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION, notification("Erro: " + (message == null ? "indisponível" : message)));
    }
    private void updateNotification() {
        String text;
        switch (reducer.state().mode) {
            case RECORDING: text = "Gravando viagem"; break;
            case LIVE_ONLY: text = "Transmissão ao vivo"; break;
            case RECORDING_LIVE: text = "Gravando e transmitindo"; break;
            default: text = reducer.state().transferActive ? "Transferindo gravação" : "Câmera pronta";
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(text));
    }
    private Notification notification(String text) {
        Intent open = new Intent(this, CameraActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Haval Camera")
                .setContentText(text).setOngoing(true).setContentIntent(pending).build();
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(new NotificationChannel(CHANNEL, "Gravação da câmera", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public void onDestroy() { main.removeCallbacks(tick); stopPipeline(); if (capture != null) capture.close(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
