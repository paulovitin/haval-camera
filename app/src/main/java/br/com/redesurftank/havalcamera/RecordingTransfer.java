package br.com.redesurftank.havalcamera;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Leased, stop-and-wait HVT1 producer. No capture, encoder, or recording failure dependency. */
public final class RecordingTransfer implements AutoCloseable {
    public static final String EXTRA_OFFSETS = "br.com.redesurftank.havalcamera.extra.OFFSETS";
    public static final String EXTRA_COMPLETED_SEGMENT_IDS = "br.com.redesurftank.havalcamera.extra.COMPLETED_SEGMENT_IDS";
    public static final String EXTRA_PAUSED = "br.com.redesurftank.havalcamera.extra.PAUSED";
    private static final Pattern PAUSE = Pattern.compile(
            "\\s*\\{\\s*\"type\"\\s*:\\s*\"pause\"\\s*,\\s*\"reason\"\\s*:\\s*\"(?:live|viewer)\"\\s*\\}\\s*");
    private static final Pattern RESUME = Pattern.compile(
            "\\s*\\{\\s*\"type\"\\s*:\\s*\"resume\"\\s*,\\s*\"offsets\"\\s*:\\s*\\{([^{}]*)\\}\\s*\\}\\s*");
    private static final Pattern OFFSET = Pattern.compile("\\s*\"([1-9][0-9]{0,9})\"\\s*:\\s*\"(0|[1-9][0-9]{0,19})\"\\s*");
    private final VideoCatalog catalog;
    private final WebSocket.Factory webSockets;
    private final Runnable onEnded;
    private final LongSupplier clock;
    private final ScheduledExecutorService worker;
    private final byte[] buffer = new byte[TransferChunkCodec.MAX_DATA_BYTES];
    private final boolean ownsFactory;
    private final Map<String, Progress> segments = new LinkedHashMap<>();
    private Ownership ownership;
    private final Set<String> consumedTokens = new java.util.HashSet<>();
    private WebSocket socket;
    private ScheduledFuture<?> expiry;
    private long generation;
    private long expiresAt;
    private boolean active;
    private boolean connected;
    private boolean paused = true;
    private boolean live;
    private boolean closed;
    private String sessionId;
    private Progress pending;
    private long pendingEnd;
    private boolean pendingEof;

    public RecordingTransfer(VideoCatalog catalog, WebSocket.Factory webSockets,
            Runnable onEnded, LongSupplier clock) {
        this(catalog, webSockets, onEnded, clock, false);
    }

    private RecordingTransfer(VideoCatalog catalog, WebSocket.Factory webSockets,
            Runnable onEnded, LongSupplier clock, boolean ownsFactory) {
        this.catalog = Objects.requireNonNull(catalog);
        this.webSockets = Objects.requireNonNull(webSockets);
        this.onEnded = Objects.requireNonNull(onEnded);
        this.clock = Objects.requireNonNull(clock);
        this.ownsFactory = ownsFactory;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CameraRecordingTransfer"); thread.setDaemon(true); return thread;
        });
    }

    public static RecordingTransfer createDefault(VideoCatalog catalog, Runnable onEnded, LongSupplier clock) {
        return new RecordingTransfer(catalog, VideoRelayClient.newWebSocketClient("CameraTransferWebSocket"),
                onEnded, clock, true);
    }

    public synchronized void start(VideoRelayClient.Session session, String tripId, List<String> ids,
            Map<String, String> offsets, Set<String> completed, boolean initiallyPaused) throws IOException {
        if (closed) throw new IllegalStateException("Transfer is closed");
        VideoRelayClient.validateSession(session, clock.getAsLong());
        if (!"/ws/video/transfer/car".equals(URI.create(session.url).getPath()))
            throw new IllegalArgumentException("Transfer producer URL required");
        if (ids == null || ids.isEmpty() || ids.size() > 256 || offsets == null || completed == null
                || ids.size() != new java.util.HashSet<>(ids).size()
                || !offsets.keySet().equals(new java.util.HashSet<>(ids)) || !ids.containsAll(completed))
            throw new IllegalArgumentException("Invalid transfer selection");
        String tokenHash = ByteString.of(sha256().digest(session.producerToken.getBytes(StandardCharsets.UTF_8))).hex();
        if (consumedTokens.contains(tokenHash)) throw new IllegalArgumentException("Fresh transfer credentials required");
        Map<String, Progress> selected = new LinkedHashMap<>();
        VideoCatalog.ReadLease acquired = catalog.acquireReadLease(tripId);
        try {
            VideoCatalog.Trip trip = catalog.completedSnapshot().stream()
                    .filter(value -> Long.toString(value.tripId).equals(tripId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Completed trip required"));
            for (String id : ids) {
                if (id == null || !id.matches("[1-9][0-9]{0,5}")) throw new IllegalArgumentException("Invalid segment ID");
                VideoCatalog.Segment metadata = trip.segments.stream()
                        .filter(value -> Integer.toString(value.segmentId).equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Completed segment required"));
                long offset = parseOffset(offsets.get(id));
                if (Long.compareUnsigned(offset, metadata.byteSize) > 0
                        || (completed.contains(id) && offset != metadata.byteSize))
                    throw new IllegalArgumentException("Invalid file offset");
                try (RandomAccessFile input = catalog.openTransferSegment(acquired, id)) { /* preflight every file */ }
                selected.put(id, new Progress(id, metadata, offset, completed.contains(id)));
            }
        } catch (IOException | RuntimeException failure) {
            acquired.close(); throw failure;
        }
        end(4000);
        segments.clear(); segments.putAll(selected);
        ownership = new Ownership(acquired); active = true; connected = false;
        // Readiness always requires an explicit resume, including when the command says false.
        paused = true; pending = null;
        sessionId = session.sessionId; expiresAt = session.expiresAtMs;
        long attempt = ++generation;
        try {
            Request request = new Request.Builder().url(session.url)
                    .header("Authorization", "Bearer " + session.producerToken).build();
            consumedTokens.add(tokenHash);
            socket = webSockets.newWebSocket(request, listener(attempt));
            expiry = worker.schedule(() -> finish(attempt, 4000), Math.max(1, expiresAt - clock.getAsLong()), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            end(4000); throw new IllegalStateException("Transfer connection unavailable");
        }
    }

    public synchronized String sessionId() { return sessionId; }

    public synchronized Map<String, String> acknowledgedOffsets() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Progress progress : segments.values()) result.put(progress.id, Long.toUnsignedString(progress.offset));
        return result;
    }

    public synchronized void setLiveActive(boolean activeLive) {
        live = activeLive;
        if (live) paused = true;
        // A resume received while local live is closing is still explicit bridge authorization.
        if (!live && !paused) pumpLater(generation, 0);
    }

    public synchronized void cancel() { end(4000); }

    private WebSocketListener listener(long attempt) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                synchronized (RecordingTransfer.this) {
                    if (!current(attempt)) { ws.close(4000, "stale transfer"); return; }
                    socket = ws; connected = true;
                }
            }
            @Override public void onMessage(WebSocket ws, String text) {
                synchronized (RecordingTransfer.this) {
                    if (!current(attempt)) return;
                    try { control(text); }
                    catch (IllegalArgumentException invalid) { end(4004); return; }
                    pumpLater(attempt, 0);
                }
            }
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                synchronized (RecordingTransfer.this) {
                    if (!current(attempt)) return;
                    try {
                        TransferChunkCodec.Message message = TransferChunkCodec.decode(bytes.asByteBuffer());
                        Progress progress = segments.get(Long.toString(message.ordinal));
                        if (progress == null) throw new IllegalArgumentException("Unknown ordinal");
                        if (message.type == TransferChunkCodec.Type.ERROR) { end(4000); return; }
                        if (message.type != TransferChunkCodec.Type.ACK || pending != progress
                                || message.offset != pendingEnd || Long.compareUnsigned(message.offset, progress.metadata.byteSize) > 0)
                            throw new IllegalArgumentException("Unexpected ACK");
                        progress.offset = message.offset;
                        if (pendingEof) progress.complete = true;
                        pending = null;
                        if (allComplete()) { end(4000); return; }
                    } catch (IllegalArgumentException malformed) { end(4004); return; }
                    pumpLater(attempt, 0);
                }
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) { finish(attempt, code); }
            @Override public void onClosed(WebSocket ws, int code, String reason) { finish(attempt, code); }
            @Override public void onFailure(WebSocket ws, Throwable failure, Response response) { finish(attempt, 4000); }
        };
    }

    private void control(String text) {
        if (text == null || text.length() > 16384) throw new IllegalArgumentException("Invalid control");
        if (PAUSE.matcher(text).matches()) { paused = true; return; }
        Matcher resume = RESUME.matcher(text);
        if (!resume.matches()) throw new IllegalArgumentException("Unsupported control");
        Map<String, String> offsets = new LinkedHashMap<>();
        String entries = resume.group(1);
        for (String entry : entries.split(",", -1)) {
            Matcher pair = OFFSET.matcher(entry);
            if (!pair.matches() || offsets.put(pair.group(1), pair.group(2)) != null)
                throw new IllegalArgumentException("Invalid offsets");
        }
        if (!offsets.keySet().equals(segments.keySet())) throw new IllegalArgumentException("Invalid membership");
        for (Progress progress : segments.values())
            if (parseOffset(offsets.get(progress.id)) != progress.offset) throw new IllegalArgumentException("Unexpected resume offset");
        // A paused frame may have been discarded by the bridge. No retry timer can clear pending.
        if (paused) pending = null;
        paused = false;
    }

    private synchronized boolean current(long attempt) { return active && generation == attempt; }
    private boolean allComplete() { return segments.values().stream().allMatch(progress -> progress.complete); }
    private void pumpLater(long attempt, long delay) {
        if (current(attempt)) worker.schedule(() -> pump(attempt), delay, TimeUnit.MILLISECONDS);
    }

    private void pump(long attempt) {
        Progress progress;
        long offset;
        Ownership reading;
        synchronized (this) {
            if (!current(attempt) || !connected || paused || live || pending != null) return;
            if (clock.getAsLong() >= expiresAt) { end(4000); return; }
            if (allComplete()) { end(4000); return; }
            try {
                if (socket.queueSize() > 0) { pumpLater(attempt, 10); return; }
            } catch (RuntimeException unavailable) { end(4000); return; }
            progress = segments.values().stream().filter(value -> !value.complete).findFirst().get();
            offset = progress.offset; reading = ownership; reading.inUse = true;
        }
        try {
            ByteBuffer frame;
            boolean eof = offset == progress.metadata.byteSize;
            // Payload reads and hashing stay on this worker, outside the service/control lock.
            try (RandomAccessFile input = catalog.openTransferSegment(reading.lease, progress.id)) {
                if (!progress.verified) {
                    MessageDigest whole = sha256();
                    while (true) {
                        synchronized (this) {
                            if (!current(attempt) || paused || live) return;
                            if (clock.getAsLong() >= expiresAt) { end(4000); return; }
                        }
                        int count = input.read(buffer);
                        if (count == -1) break;
                        whole.update(buffer, 0, count);
                    }
                    if (!MessageDigest.isEqual(whole.digest(), progress.digest)) throw new IOException("Segment integrity failure");
                    progress.verified = true;
                }
                input.seek(offset);
                if (eof) frame = TransferChunkCodec.encodeEof(progress.metadata.segmentId, offset, progress.digest);
                else {
                    int count = (int) Math.min(buffer.length, progress.metadata.byteSize - offset);
                    input.readFully(buffer, 0, count);
                    frame = TransferChunkCodec.encodeData(progress.metadata.segmentId, offset,
                            count == buffer.length ? buffer : Arrays.copyOf(buffer, count));
                }
            }
            synchronized (this) {
                if (!current(attempt) || paused || live || pending != null || progress.offset != offset) return;
                if (clock.getAsLong() >= expiresAt) { end(4000); return; }
                if (socket.queueSize() > 0) { pumpLater(attempt, 10); return; }
                pending = progress; pendingEof = eof;
                pendingEnd = eof ? offset : offset + frame.remaining() - TransferChunkCodec.HEADER_BYTES;
                if (!socket.send(ByteString.of(frame))) end(4000);
            }
        } catch (IOException | RuntimeException failure) {
            finish(attempt, 4000);
        } finally {
            synchronized (this) {
                reading.inUse = false;
                if (reading.ended) release(reading);
            }
        }
    }

    private synchronized void finish(long attempt, int code) { if (current(attempt)) end(code); }

    private void end(int code) {
        if (!active) return;
        active = false; connected = false; paused = true; pending = null;
        if (expiry != null) expiry.cancel(false);
        expiry = null;
        WebSocket old = socket; socket = null;
        Ownership ended = ownership; ownership = null; ended.ended = true;
        try { if (old != null) old.close(code >= 4000 && code <= 4008 ? code : 4000, "transfer ended"); }
        catch (RuntimeException ignored) { /* Terminal state and lease cleanup do not depend on transport. */ }
        if (!ended.inUse) release(ended);
    }

    private void release(Ownership ended) {
        if (ended.released) return;
        ended.released = true; ended.lease.close();
        onEnded.run();
    }

    private static final class Ownership {
        final VideoCatalog.ReadLease lease;
        boolean inUse;
        boolean ended;
        boolean released;
        Ownership(VideoCatalog.ReadLease lease) { this.lease = lease; }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; end(4000); generation++; worker.shutdownNow();
        if (ownsFactory && webSockets instanceof OkHttpClient) {
            OkHttpClient client = (OkHttpClient) webSockets;
            client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
        }
    }

    private static long parseOffset(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid u64 offset");
        return Long.parseUnsignedLong(value);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class Progress {
        final String id;
        final VideoCatalog.Segment metadata;
        final byte[] digest;
        long offset;
        boolean complete;
        boolean verified;
        Progress(String id, VideoCatalog.Segment metadata, long offset, boolean complete) {
            this.id = id; this.metadata = metadata; this.offset = offset; this.complete = complete;
            digest = new byte[32];
            for (int i = 0; i < digest.length; i++) digest[i] = (byte) Integer.parseInt(metadata.sha256.substring(i * 2, i * 2 + 2), 16);
        }
    }
}
