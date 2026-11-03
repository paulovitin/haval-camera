package br.com.redesurftank.havalcamera;

import java.util.Objects;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Routes codec output without allowing an optional live consumer to delay recording. */
public final class RecordingCoordinator implements AutoCloseable {
    public interface Sink extends AutoCloseable {
        void write(EncodedAccessUnit unit) throws Exception;

        @Override default void close() throws Exception { }
    }

    public interface CodecSink extends Sink {
        void onCodecConfig(byte[] sps, byte[] pps) throws Exception;
    }

    public interface DropAwareSink extends Sink {
        void onDroppedAccessUnit();
    }

    public interface Capture {
        void stop();
    }

    public interface EncoderControl {
        void requestKeyFrame();
    }

    public interface FailureListener {
        void onFailure(Throwable failure);
    }

    private final Sink recorder;
    private final Capture capture;
    private final EncoderControl encoder;
    private final FailureListener failures;
    private final ThreadPoolExecutor liveExecutor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "CameraLiveSink");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Sink live;
    private byte[] latestSps;
    private byte[] latestPps;
    private volatile boolean running = true;

    public RecordingCoordinator(
            Sink recorder, Capture capture, EncoderControl encoder, FailureListener failures) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public void onEncodedAccessUnit(EncodedAccessUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (!running) return;
        try {
            recorder.write(unit);
        } catch (Exception failure) {
            failures.onFailure(failure);
        }

        Sink destination = live;
        if (destination == null) return;
        try {
            liveExecutor.execute(() -> writeLive(destination, unit));
        } catch (java.util.concurrent.RejectedExecutionException dropped) {
            if (destination instanceof DropAwareSink)
                ((DropAwareSink) destination).onDroppedAccessUnit();
            else encoder.requestKeyFrame();
        }
    }

    public synchronized void attachLiveSink(Sink sink) {
        Objects.requireNonNull(sink, "sink");
        if (live != sink) closeLive(live);
        live = sink;
        if (sink instanceof CodecSink && latestSps != null && latestPps != null) {
            try { ((CodecSink) sink).onCodecConfig(latestSps, latestPps); }
            catch (Exception failure) {
                live = null;
                closeLive(sink);
                return;
            }
        }
        encoder.requestKeyFrame();
    }

    public synchronized void onCodecConfig(byte[] sps, byte[] pps) {
        latestSps = Arrays.copyOf(Objects.requireNonNull(sps, "sps"), sps.length);
        latestPps = Arrays.copyOf(Objects.requireNonNull(pps, "pps"), pps.length);
        if (live instanceof CodecSink) {
            try { ((CodecSink) live).onCodecConfig(latestSps, latestPps); }
            catch (Exception failure) {
                Sink failed = live;
                live = null;
                closeLive(failed);
            }
        }
    }

    public synchronized Sink releaseLiveSink() {
        Sink released = live;
        live = null;
        return released;
    }

    public synchronized void detachLiveSink() {
        Sink detached = live;
        live = null;
        closeLive(detached);
    }

    public synchronized void onCodecFailure(Throwable failure) {
        if (!running) return;
        running = false;
        capture.stop();
        detachLiveSink();
        failures.onFailure(Objects.requireNonNull(failure, "failure"));
    }

    public boolean isRunning() {
        return running;
    }

    private void writeLive(Sink destination, EncodedAccessUnit unit) {
        if (destination != live) return;
        try {
            destination.write(unit);
        } catch (Exception failure) {
            synchronized (this) {
                if (live == destination) live = null;
            }
            closeLive(destination);
        }
    }

    private void closeLive(Sink sink) {
        if (sink == null) return;
        try {
            sink.close();
        } catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        if (!running && liveExecutor.isShutdown()) return;
        running = false;
        detachLiveSink();
        liveExecutor.shutdownNow();
        try {
            recorder.close();
        } catch (Exception failure) {
            failures.onFailure(failure);
        }
    }
}
