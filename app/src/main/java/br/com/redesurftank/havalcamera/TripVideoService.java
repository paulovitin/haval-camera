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
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.net.Uri;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Foreground owner of recording and authenticated live-video lifecycle. */
public final class TripVideoService extends Service {
    private static final String CHANNEL = "trip-video";
    private static final int NOTIFICATION = 41;
    private static final String PREFERENCES = "trip-video-journal";
    private static final String ACTIVE_ID = "service.activeTripId";
    private static final String ACTIVE_MANUAL = "service.activeManual";
    private static final String START_PREFIX = "service.tripStart.";
    private final ServiceStateReducer reducer = new ServiceStateReducer();
    private final TelemetryOverlayModel telemetry = new TelemetryOverlayModel();
    private VideoCatalog catalog;
    private VideoSettings settings;
    private CommandJournal journal;
    private CameraCaptureClient capture;
    private EglFrameEncoder encoder;
    private SegmentMuxer muxer;
    private RecordingCoordinator coordinator;
    private VideoRelayClient liveClient;
    private RecordingTransfer transfer;
    private VideoRelayClient.Session pendingLiveSession;
    private final LiveSessionPolicy liveSessionPolicy = new LiveSessionPolicy();
    private String activeTripId;
    private final FrameRouter frames = new FrameRouter();
    private boolean captureRunning;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { apply(ServiceStateReducer.Event.tick(nextSequence(), System.currentTimeMillis())); }
            catch (Exception failure) { fail(failure.getMessage()); }
            updateOverlay();
            main.postDelayed(this, 1_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION, notification("Câmera pronta"));
        try {
            catalog = VideoCatalog.shared(getFilesDir());
            catalog.setOnChanged(() -> main.post(() -> getContentResolver().notifyChange(
                    Uri.parse("content://" + VideoCatalogProvider.AUTHORITY + "/trips"), null)));
            settings = VideoSettings.from(this);
            journal = new CommandJournal(getSharedPreferences(PREFERENCES, MODE_PRIVATE));
            capture = new CameraCaptureClient(this, (state, detail) -> {
                if (state == CameraCaptureClient.State.ERROR) pipelineFailure(new IOException(detail));
            });
            VideoSettings.Snapshot initial = settings.snapshot();
            apply(ServiceStateReducer.Event.settings(nextSequence(), initial.automaticRecording,
                    initial.recordParkedLive, initial.remoteLive, initial.remoteDownloads,
                    journal.activeTripId()));
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
        catch (Exception failure) {
            if (VideoContract.ACTION_TRANSFER.equals(intent.getAction())
                    || VideoContract.ACTION_CANCEL_TRANSFER.equals(intent.getAction())) {
                acknowledgeError(intent, new IOException("Transferência indisponível"));
            } else {
                acknowledgeError(intent, failure);
                fail(failure.getMessage());
            }
        }
        return START_STICKY;
    }

    private void route(Intent intent) throws Exception {
        String action = intent.getAction();
        if (VideoContract.ACTION_REFRESH_SETTINGS.equals(action)) {
            VideoSettings.Snapshot value = settings.reload();
            apply(ServiceStateReducer.Event.settings(nextSequence(), value.automaticRecording,
                    value.recordParkedLive, value.remoteLive, value.remoteDownloads,
                    journal.activeTripId()));
            updateOverlay();
            return;
        }
        if (VideoContract.ACTION_START_MANUAL_RECORDING.equals(action)) {
            if (reducer.state().tripId != null || reducer.state().authoritativeTripId != null) return;
            long start = intent.getLongExtra(VideoContract.EXTRA_START_MS, System.currentTimeMillis());
            persistTripStart(Long.toString(start), start);
            apply(ServiceStateReducer.Event.startManual(Long.toString(start), nextSequence()));
            return;
        }
        if (VideoContract.ACTION_STOP_MANUAL_RECORDING.equals(action)) {
            long endMs = requireEndMs(intent, reducer.state().tripId);
            apply(ServiceStateReducer.Event.stopManual(endMs, nextSequence()));
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
        if (command == VideoContract.Command.START_LIVE)
            liveSessionPolicy.requireStartAllowed(reducer.state());
        if (command == VideoContract.Command.CANCEL_TRANSFER && transfer != null
                && !transfer.sessionId().equals(intent.getStringExtra(VideoContract.EXTRA_SESSION_ID)))
            throw new IllegalArgumentException("Transfer session mismatch");
        if (command == VideoContract.Command.STOP_LIVE)
            liveSessionPolicy.requireMatchingStop(
                    intent.getStringExtra(VideoContract.EXTRA_SESSION_ID));
        final VideoRelayClient.Session requestedLive = command == VideoContract.Command.START_LIVE
                ? requireLiveSession(intent) : null;
        final long stagedStartMs;
        if (command == VideoContract.Command.START_TRIP) stagedStartMs = requireStartMs(intent);
        else stagedStartMs = -1;
        final long suppliedEndMs;
        if (command == VideoContract.Command.STOP_TRIP) suppliedEndMs = requireEndMs(intent, tripId);
        else suppliedEndMs = -1;
        CommandJournal.Decision decision = command == VideoContract.Command.START_TRIP
                ? journal.applyStaged(revision, requestId, command, tripId,
                        editor -> editor.putLong(START_PREFIX + tripId, stagedStartMs))
                : journal.apply(revision, requestId, command, tripId);
        if (decision.outcome != CommandJournal.Outcome.APPLIED
                && decision.outcome != CommandJournal.Outcome.NO_OP) {
            acknowledge(requestId, revision, decision.revision,
                    CommandAcknowledgement.statusFor(decision.outcome), null);
            return;
        }
        switch (command) {
            case START_TRIP:
                if (decision.previousTripId != null && !decision.previousTripId.equals(tripId)) {
                    apply(ServiceStateReducer.Event.stopAutomatic(decision.previousTripId, nextSequence()));
                }
                apply(ServiceStateReducer.Event.startAutomatic(tripId, nextSequence()));
                break;
            case RECONCILE:
                if (decision.previousTripId != null && !decision.previousTripId.equals(tripId)) {
                    apply(ServiceStateReducer.Event.stopAutomatic(decision.previousTripId, nextSequence()));
                }
                if (tripId == null) break;
                recoverReconcileStart(intent, tripId);
                apply(ServiceStateReducer.Event.startAutomatic(tripId, nextSequence()));
                break;
            case STOP_TRIP:
                apply(ServiceStateReducer.Event.stopAutomatic(tripId, suppliedEndMs, nextSequence()));
                break;
            case DISCARD_TRIP:
                apply(ServiceStateReducer.Event.stopAutomatic(tripId, nextSequence()));
                discardCompletedOrActive(tripId);
                break;
            case START_LIVE:
                if (transfer != null) transfer.setLiveActive(true);
                closeLiveClient();
                pendingLiveSession = requestedLive;
                apply(ServiceStateReducer.Event.viewerConnected(nextSequence(), System.currentTimeMillis()));
                liveSessionPolicy.confirmStarted(requestedLive.sessionId, reducer.state());
                try { if (liveClient == null) attachLive(); }
                catch (RuntimeException failure) {
                    closeLiveClient();
                    if (transfer != null) transfer.setLiveActive(false);
                    throw failure;
                }
                break;
            case STOP_LIVE:
                closeLiveClient();
                if (transfer != null) transfer.setLiveActive(false);
                apply(ServiceStateReducer.Event.viewerLost(nextSequence(), System.currentTimeMillis()));
                break;
            case TRANSFER:
                startTransfer(intent); break;
            case CANCEL_TRANSFER:
                closeTransfer();
                apply(ServiceStateReducer.Event.transferStopped(nextSequence())); break;
            case DELETE_COMPLETED:
                VideoContract.requireConfirmedDelete(intent.getBooleanExtra(VideoContract.EXTRA_DELETE_CONFIRMED, false));
                deleteCompleted(); break;
            default: break;
        }
        acknowledge(requestId, revision, decision.revision,
                CommandAcknowledgement.statusFor(decision.outcome), null);
    }

    private void acknowledgeError(Intent request, Exception failure) {
        String requestId = request.getStringExtra(VideoContract.EXTRA_REQUEST_ID);
        if (requestId == null || requestId.isEmpty()) return;
        long requestRevision = request.getLongExtra(VideoContract.EXTRA_STATE_REVISION, -1);
        acknowledge(requestId, requestRevision, journal == null ? -1 : journal.revision(),
                VideoContract.STATUS_ERROR, failure == null ? null : failure.getMessage());
    }

    private void acknowledge(String requestId, long requestRevision, long cameraRevision,
            String status, String error) {
        Intent result = new Intent(VideoContract.ACTION_COMMAND_RESULT)
                .setPackage("br.com.redesurftank.ecotrip")
                .putExtra(VideoContract.EXTRA_PROTOCOL_VERSION, VideoContract.PROTOCOL_VERSION)
                .putExtra(VideoContract.EXTRA_REQUEST_ID, requestId)
                .putExtra(VideoContract.EXTRA_STATE_REVISION, requestRevision)
                .putExtra(VideoContract.EXTRA_CAMERA_REVISION, cameraRevision)
                .putExtra(VideoContract.EXTRA_COMMAND_STATUS, status);
        if (error != null) result.putExtra(VideoContract.EXTRA_ERROR_MESSAGE, error);
        sendBroadcast(result, VideoContract.CONTROL_PERMISSION);
    }

    private long sequence;
    private long nextSequence() { return ++sequence; }

    private void apply(ServiceStateReducer.Event event) throws Exception {
        RetryableStartEffect.apply(reducer, event, nextSequence(), this::execute);
        updateNotification();
    }

    private void execute(ServiceStateReducer.Effect effect) throws Exception {
        switch (effect.type) {
            case START_RECORDING: startRecording(effect.tripId, reducer.state().manualRecording); break;
            case STOP_RECORDING:
                stopPipeline(hasLogicalViewer());
                if (activeTripId != null && activeTripId.equals(effect.tripId)) {
                    catalog.completeTrip(activeTripId,
                            effect.endMs >= 0 ? effect.endMs : System.currentTimeMillis());
                    activeTripId = null;
                    clearPersistedRecording();
                }
                break;
            case PAUSE_RECORDING:
                stopPipeline(hasLogicalViewer()); activeTripId = null; clearPersistedRecording(); break;
            case FINALIZE_TRIP: finalizeCatalogTrip(effect.tripId,
                    effect.endMs >= 0 ? effect.endMs : System.currentTimeMillis()); break;
            case START_CAPTURE:
                if (effect.tripId == null && coordinator == null) startLiveOnly();
                break;
            case STOP_CAPTURE: stopPipeline(false); break;
            case ATTACH_LIVE: break;
            case DETACH_LIVE: closeLiveClient(); break;
            case PAUSE_TRANSFER: if (transfer != null) transfer.setLiveActive(true); break;
            case RESUME_TRANSFER: if (transfer != null) transfer.setLiveActive(false); break;
            case CANCEL_TRANSFER: closeTransfer(); break;
            default: break;
        }
    }

    private void startRecording(String tripId, boolean manual) throws Exception {
        if (tripId.equals(activeTripId) && coordinator != null) return;
        stopPipeline(liveClient != null);
        long startMs = tripStart(tripId);
        try { catalog.beginTrip(tripId, startMs,
                manual ? VideoCatalog.Source.MANUAL : VideoCatalog.Source.AUTOMATIC); }
        catch (IllegalStateException existing) {
            boolean found = catalog.localSnapshot().stream().anyMatch(trip -> Long.toString(trip.tripId).equals(tripId) && !trip.completed);
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
            @Override public void onCodecConfig(byte[] sps, byte[] pps) {
                RecordingCoordinator current = coordinator;
                if (current != null) current.onCodecConfig(sps, pps);
            }
            @Override public void onAccessUnit(EncodedAccessUnit unit) { if (coordinator != null) coordinator.onEncodedAccessUnit(unit); }
            @Override public void onFailure(Throwable failure) { if (coordinator != null) coordinator.onCodecFailure(failure); }
        }, fps, bitrate);
        coordinator = new RecordingCoordinator(output, this::forceStopCapture, encoder, this::pipelineFailure);
        activeTripId = tripId;
        if (!getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(ACTIVE_ID, tripId).putBoolean(ACTIVE_MANUAL, manual).commit()) {
            throw new IllegalStateException("Could not persist active recording");
        }
        encoder.start();
        if (liveClient != null) coordinator.attachLiveSink(liveClient);
        updateOverlay();
        long origin = System.nanoTime();
        frames.setEncoder(frame -> {
            EglFrameEncoder current = encoder;
            if (current != null) current.queueFrame(frame, (System.nanoTime() - origin) / 1_000);
        });
        ensureCapture();
    }

    private void startLiveOnly() throws Exception {
        RecordingCoordinator.Sink discard = unit -> { };
        VideoSettings.Snapshot selected = settings.snapshot();
        int fps = selected.quality == VideoSettings.Quality.ECONOMY ? 10 : 15;
        int bitrate = selected.quality == VideoSettings.Quality.ECONOMY ? 2_000_000
                : selected.quality == VideoSettings.Quality.HIGH ? 6_000_000 : 4_000_000;
        encoder = new EglFrameEncoder(new EglFrameEncoder.Listener() {
            @Override public void onOutputFormat(MediaFormat format) { }
            @Override public void onCodecConfig(byte[] sps, byte[] pps) {
                RecordingCoordinator current = coordinator;
                if (current != null) current.onCodecConfig(sps, pps);
            }
            @Override public void onAccessUnit(EncodedAccessUnit unit) { if (coordinator != null) coordinator.onEncodedAccessUnit(unit); }
            @Override public void onFailure(Throwable failure) { if (coordinator != null) coordinator.onCodecFailure(failure); }
        }, fps, bitrate);
        coordinator = new RecordingCoordinator(discard, this::forceStopCapture, encoder, this::pipelineFailure);
        encoder.start();
        if (liveClient != null) coordinator.attachLiveSink(liveClient);
        updateOverlay();
        long origin = System.nanoTime();
        frames.setEncoder(frame -> { if (encoder != null) encoder.queueFrame(frame, (System.nanoTime()-origin)/1_000); });
        ensureCapture();
    }

    private void stopPipeline(boolean preserveLive) {
        if (preserveLive && coordinator != null) {
            coordinator.releaseLiveSink();
            if (liveClient != null) liveClient.resetCodec();
        }
        frames.setEncoder(null);
        EglFrameEncoder oldEncoder = encoder; encoder = null;
        if (oldEncoder != null) oldEncoder.close();
        RecordingCoordinator oldCoordinator = coordinator; coordinator = null;
        if (oldCoordinator != null) oldCoordinator.close();
        if (!preserveLive) closeLiveClient();
        muxer = null;
        stopCaptureIfUnused();
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
            updateOverlay();
        }
    }

    private void deleteCompleted() throws IOException {
        if (reducer.state().transferActive) throw new IOException("Transferência ativa");
        for (VideoCatalog.Trip trip : catalog.completedSnapshot())
            if (!catalog.leasedTripIds().contains(trip.tripId)) catalog.discardTrip(Long.toString(trip.tripId));
    }

    private void enforceStorageReserve() throws IOException {
        VideoSettings.Snapshot value = settings.reload();
        catalog.enforceStorageReserve(() -> getFilesDir().getUsableSpace(), value.reserveBytes,
                value.automaticDeletion, activeTripId == null ? null : Long.parseLong(activeTripId));
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

    private void pipelineFailure(Throwable failure) {
        main.post(() -> {
            try { apply(ServiceStateReducer.Event.pipelineFailed(nextSequence(),
                    failure == null ? null : failure.getMessage())); }
            catch (Exception nested) { fail(nested.getMessage()); }
        });
    }

    private void startTransfer(Intent intent) throws Exception {
        if (!settings.snapshot().remoteDownloads) throw new IllegalArgumentException("Downloads disabled");
        VideoRelayClient.Session session = new VideoRelayClient.Session(
                intent.getStringExtra(VideoContract.EXTRA_SESSION_URL),
                intent.getStringExtra(VideoContract.EXTRA_PRODUCER_TOKEN),
                intent.getLongExtra(VideoContract.EXTRA_EXPIRES_AT_MS, -1));
        if (!session.sessionId.equals(intent.getStringExtra(VideoContract.EXTRA_SESSION_ID))
                || !session.sessionId.equals(intent.getStringExtra(VideoContract.EXTRA_VIEWER_SESSION)))
            throw new IllegalArgumentException("Transfer session mismatch");
        ArrayList<String> ids = intent.getStringArrayListExtra(VideoContract.EXTRA_SEGMENT_IDS);
        java.util.Map<String, String> offsets = new java.util.LinkedHashMap<>();
        Bundle supplied = intent.getBundleExtra(RecordingTransfer.EXTRA_OFFSETS);
        if (supplied == null || supplied.size() > 256) throw new IllegalArgumentException("Transfer offsets required");
        for (String id : supplied.keySet()) offsets.put(id, supplied.getString(id));
        ArrayList<String> completed = intent.getStringArrayListExtra(RecordingTransfer.EXTRA_COMPLETED_SEGMENT_IDS);
        closeTransfer();
        final RecordingTransfer[] holder = new RecordingTransfer[1];
        RecordingTransfer created = RecordingTransfer.createDefault(catalog,
                () -> main.post(() -> transferEnded(holder[0])), System::currentTimeMillis);
        holder[0] = created;
        created.setLiveActive(liveClient != null || pendingLiveSession != null);
        try {
            created.start(session, intent.getStringExtra(VideoContract.EXTRA_TRIP_ID), ids, offsets,
                    completed == null ? java.util.Collections.emptySet() : new java.util.HashSet<>(completed),
                    intent.getBooleanExtra(RecordingTransfer.EXTRA_PAUSED, true));
        } catch (Exception failure) {
            created.close();
            apply(ServiceStateReducer.Event.transferStopped(nextSequence()));
            throw failure;
        }
        transfer = created;
        apply(ServiceStateReducer.Event.transferStarted(nextSequence()));
    }

    private void transferEnded(RecordingTransfer expected) {
        if (expected == null || transfer != expected) return;
        closeTransfer();
        try { apply(ServiceStateReducer.Event.transferStopped(nextSequence())); }
        catch (Exception failure) { updateNotification(); }
    }

    private void closeTransfer() {
        RecordingTransfer old = transfer; transfer = null;
        if (old != null) old.close();
    }

    private VideoRelayClient.Session requireLiveSession(Intent intent) {
        String url = intent.getStringExtra(VideoContract.EXTRA_SESSION_URL);
        String token = intent.getStringExtra(VideoContract.EXTRA_PRODUCER_TOKEN);
        long expiresAt = intent.getLongExtra(VideoContract.EXTRA_EXPIRES_AT_MS, -1);
        VideoRelayClient.Session session = new VideoRelayClient.Session(url, token, expiresAt);
        VideoRelayClient.validateSession(session, System.currentTimeMillis());
        return session;
    }

    private void attachLive() {
        if (pendingLiveSession == null) return;
        if (coordinator == null) throw new IllegalStateException("Pipeline ao vivo indisponível");
        VideoRelayClient.Session session = pendingLiveSession;
        pendingLiveSession = null;
        final VideoRelayClient[] holder = new VideoRelayClient[1];
        VideoRelayClient created = VideoRelayClient.createDefault(
                () -> {
                    EglFrameEncoder current = encoder;
                    if (current != null) current.requestKeyFrame();
                },
                (status, detail) -> {
                    if (status == VideoRelayClient.Status.CONNECTING
                            || status == VideoRelayClient.Status.CONNECTED) return;
                    VideoRelayClient expected = holder[0];
                    main.post(() -> liveEnded(expected));
                }, System::currentTimeMillis);
        holder[0] = created;
        try {
            created.start(session);
            liveClient = created;
            coordinator.attachLiveSink(created);
        } catch (RuntimeException failure) {
            created.close();
            throw failure;
        }
    }

    private void liveEnded(VideoRelayClient expected) {
        if (expected == null || liveClient != expected) return;
        closeLiveClient();
        if (transfer != null) transfer.setLiveActive(false);
        try { apply(ServiceStateReducer.Event.viewerLost(nextSequence(), System.currentTimeMillis())); }
        catch (Exception failure) { fail(failure.getMessage()); }
    }

    private void closeLiveClient() {
        pendingLiveSession = null;
        liveSessionPolicy.clear();
        VideoRelayClient old = liveClient;
        liveClient = null;
        if (old == null) return;
        if (coordinator != null) coordinator.releaseLiveSink();
        old.close();
    }

    private boolean hasLogicalViewer() {
        return reducer.state().mode == ServiceStateReducer.Mode.LIVE_ONLY
                || reducer.state().mode == ServiceStateReducer.Mode.RECORDING_LIVE;
    }

    private void fail(String message) {
        Log.e("HavalCamera", "Video service: " + message);
        if (Build.VERSION.SDK_INT >= 26) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION, notification("Erro: " + (message == null ? "indisponível" : message)));
    }
    private void updateNotification() {
        if (reducer.state().error != null) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                    NOTIFICATION, notification("Erro: " + reducer.state().error));
            return;
        }
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
    private void ensureCapture() {
        if (captureRunning || capture == null) return;
        captureRunning = true; capture.start(frames::publish);
    }
    private void stopCaptureIfUnused() {
        if (encoder != null || frames.previewSubscriberCount() > 0 || !captureRunning || capture == null) return;
        captureRunning = false; capture.stop();
    }
    private void forceStopCapture() {
        if (!captureRunning || capture == null) return;
        captureRunning = false; capture.stop();
    }
    private void updateOverlay() {
        if (encoder == null || settings == null) return;
        VideoSettings.Snapshot value = settings.snapshot();
        encoder.setOverlaySnapshot(telemetry.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                new TelemetryOverlayModel.Settings(value.overlayMaster, value.overlayDateTime,
                        value.overlaySpeed, value.overlayAcceleration, value.overlayDriveState,
                        value.overlayDecelerationRegen,
                        TelemetryOverlayModel.Position.valueOf(value.overlayPosition.name()))));
    }
    private long requireStartMs(Intent intent) {
        return TripTimestamps.requireStart(intent.hasExtra(VideoContract.EXTRA_START_MS),
                intent.getLongExtra(VideoContract.EXTRA_START_MS, -1), System.currentTimeMillis());
    }
    private long requireEndMs(Intent intent, String tripId) {
        long start = tripId == null ? 0 : getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getLong(START_PREFIX + tripId, 0);
        return TripTimestamps.requireEnd(intent.hasExtra(VideoContract.EXTRA_END_MS),
                intent.getLongExtra(VideoContract.EXTRA_END_MS, -1), start, System.currentTimeMillis());
    }
    private long recoverReconcileStart(Intent intent, String tripId) {
        if (intent.hasExtra(VideoContract.EXTRA_START_MS)) {
            long value = requireStartMs(intent);
            persistTripStart(tripId, value);
            return value;
        }
        try { return tripStart(tripId); }
        catch (IllegalStateException missing) {
            long value = catalog.tripStartMs(tripId);
            persistTripStart(tripId, value);
            return value;
        }
    }
    private void persistTripStart(String tripId, long startMs) {
        if (!getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putLong(START_PREFIX + tripId, startMs).commit())
            throw new IllegalStateException("Could not persist trip start");
    }
    private long tripStart(String tripId) {
        long value = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getLong(START_PREFIX + tripId, -1);
        if (value < 0) throw new IllegalStateException("Missing trip start timestamp");
        return value;
    }
    private void finalizeCatalogTrip(String tripId, long endMs) throws Exception {
        if (tripId == null) return;
        if (tripId.equals(activeTripId)) { stopPipeline(hasLogicalViewer()); activeTripId = null; clearPersistedRecording(); }
        for (VideoCatalog.Trip trip : catalog.localSnapshot())
            if (Long.toString(trip.tripId).equals(tripId) && !trip.completed) catalog.completeTrip(tripId, endMs);
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().remove(START_PREFIX + tripId).commit();
    }
    private void discardCompletedOrActive(String tripId) throws Exception {
        if (tripId.equals(activeTripId)) { stopPipeline(hasLogicalViewer()); activeTripId = null; clearPersistedRecording(); }
        catalog.discardTrip(tripId);
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().remove(START_PREFIX + tripId).commit();
    }
    public final class LocalBinder extends Binder {
        /** Read on the main thread immediately before an updater action; never stops capture. */
        public boolean isUpdateIdle() {
            if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
            return UpdateVerifier.idle(capture != null && settings != null && catalog != null,
                    captureRunning, reducer.state().mode != ServiceStateReducer.Mode.IDLE
                            || activeTripId != null || encoder != null || coordinator != null);
        }
        /** Explicit updater consent closes viewers, never a recording or its encoder. */
        public void closeViewingForUpdate() throws Exception {
            if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
            frames.closePreviews();
            closeLiveClient();
            apply(ServiceStateReducer.Event.viewerDismissed(nextSequence(), System.currentTimeMillis()));
            stopCaptureIfUnused();
        }
        public FrameRouter.Subscription subscribePreview(java.util.function.Consumer<byte[]> consumer) {
            FrameRouter.Subscription subscription = frames.subscribe(consumer); ensureCapture();
            return () -> { subscription.close(); stopCaptureIfUnused(); };
        }
    }
    private final LocalBinder binder = new LocalBinder();
    @Override public void onDestroy() { closeTransfer(); if (catalog != null) catalog.setOnChanged(null); main.removeCallbacks(tick); frames.setEncoder(null); stopPipeline(false); if (capture != null) { forceStopCapture(); capture.close(); } super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return binder; }
}
