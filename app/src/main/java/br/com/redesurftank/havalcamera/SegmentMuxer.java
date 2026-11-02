package br.com.redesurftank.havalcamera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Serializes MP4 work and catalog promotion away from the codec drain thread. */
public final class SegmentMuxer implements RecordingCoordinator.Sink {
    public interface FailureListener {
        void onFailure(Throwable failure);
    }

    private static final Object STOP = new Object();
    private final VideoCatalog catalog;
    private final String tripId;
    private final File partial;
    private final VideoCatalog.Codec codecMetadata;
    private final FailureListener failures;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(120);
    private final SegmentRotationPolicy rotation = new SegmentRotationPolicy();
    private final Thread worker;
    private volatile boolean accepting = true;
    private MediaFormat outputFormat;
    private MediaMuxer muxer;
    private int track = -1;
    private long segmentStartPtsUs;
    private long segmentStartMs;
    private long lastPtsUs;
    private long timelineStartPtsUs = -1;
    private long timelineStartMs;

    public SegmentMuxer(
            File filesDirectory,
            String tripId,
            VideoCatalog catalog,
            FailureListener failures) throws IOException {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.failures = Objects.requireNonNull(failures, "failures");
        if (tripId == null || !tripId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid numeric trip ID");
        }
        this.tripId = tripId;
        File files = Objects.requireNonNull(filesDirectory, "filesDirectory").getCanonicalFile();
        File root = new File(files, "trip-video").getCanonicalFile();
        if (!files.equals(root.getParentFile())) throw new IOException("Unsafe catalog root");
        File trip = new File(root, tripId).getCanonicalFile();
        if (!root.equals(trip.getParentFile())) throw new IOException("Unsafe trip path");
        partial = new File(trip, "current.partial").getCanonicalFile();
        if (!trip.equals(partial.getParentFile())) throw new IOException("Unsafe segment path");
        codecMetadata = new VideoCatalog.Codec(
                EglFrameEncoder.MIME,
                EglFrameEncoder.WIDTH,
                EglFrameEncoder.HEIGHT,
                EglFrameEncoder.FRAME_RATE,
                EglFrameEncoder.BIT_RATE);
        worker = new Thread(this::workLoop, "CameraSegmentMuxer");
        worker.start();
    }

    public void setOutputFormat(MediaFormat format) throws InterruptedException {
        if (!accepting) throw new IllegalStateException("Muxer is closed");
        queue.put(Objects.requireNonNull(format, "format"));
    }

    @Override public void write(EncodedAccessUnit unit) throws InterruptedException {
        if (!accepting) throw new IllegalStateException("Muxer is closed");
        queue.put(Objects.requireNonNull(unit, "unit"));
    }

    private void workLoop() {
        try {
            while (true) {
                Object next = queue.take();
                if (next == STOP) break;
                if (next instanceof MediaFormat) {
                    if (outputFormat != null) throw new IllegalStateException("Codec format changed");
                    outputFormat = (MediaFormat) next;
                } else {
                    writeOnWorker((EncodedAccessUnit) next);
                }
            }
            finishSegment();
        } catch (Throwable failure) {
            accepting = false;
            releaseMuxer();
            failures.onFailure(failure);
        }
    }

    private void writeOnWorker(EncodedAccessUnit unit) throws IOException {
        if (outputFormat == null) throw new IllegalStateException("Codec format is not known");
        if (muxer == null) {
            if (!unit.keyframe) return;
            rotation.shouldRotate(unit.presentationTimeUs, true);
            openSegment(unit.presentationTimeUs);
        } else if (rotation.shouldRotate(unit.presentationTimeUs, unit.keyframe)) {
            finishSegment();
            openSegment(unit.presentationTimeUs);
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int flags = unit.keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        if (unit.endOfStream) flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
        info.set(0, unit.size(), unit.presentationTimeUs, flags);
        ByteBuffer data = unit.data();
        muxer.writeSampleData(track, data, info);
        lastPtsUs = unit.presentationTimeUs;
    }

    private void openSegment(long firstPtsUs) throws IOException {
        if (!partial.getParentFile().isDirectory()) {
            throw new IOException("Trip directory does not exist");
        }
        if (partial.exists()) throw new IOException("current.partial already exists");
        muxer = new MediaMuxer(partial.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        track = muxer.addTrack(outputFormat);
        muxer.start();
        segmentStartPtsUs = firstPtsUs;
        lastPtsUs = firstPtsUs;
        if (timelineStartPtsUs < 0) {
            timelineStartPtsUs = firstPtsUs;
            timelineStartMs = System.currentTimeMillis();
        }
        segmentStartMs = timelineStartMs + (firstPtsUs - timelineStartPtsUs) / 1_000;
    }

    private void finishSegment() throws IOException {
        if (muxer == null) return;
        MediaMuxer completed = muxer;
        muxer = null;
        track = -1;
        try {
            completed.stop();
        } finally {
            completed.release();
        }
        long durationUs = Math.max(0, lastPtsUs - segmentStartPtsUs);
        long endMs = segmentStartMs + durationUs / 1_000;
        String sha256 = sha256(partial);
        catalog.completeSegment(tripId, new VideoCatalog.SegmentMetadata(
                segmentStartMs,
                endMs,
                durationUs,
                partial.length(),
                sha256,
                codecMetadata));
    }

    private void releaseMuxer() {
        if (muxer == null) return;
        try { muxer.stop(); } catch (Exception ignored) { }
        try { muxer.release(); } catch (Exception ignored) { }
        muxer = null;
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value));
        return hex.toString();
    }

    @Override public void close() throws InterruptedException {
        if (!accepting) return;
        accepting = false;
        queue.put(STOP);
        worker.join();
    }
}
