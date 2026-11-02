package br.com.redesurftank.havalcamera;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One-session authenticated HVC1 producer. Tokens are never retried or logged. */
public final class VideoRelayClient implements RecordingCoordinator.CodecSink {
    private static final int NORMAL_CLOSE = 4000;
    private static final long PING_INTERVAL_SECONDS = 10;

    public enum Status {
        CONNECTING, CONNECTED, SESSION_ENDED, AUTH_FAILED, TOKEN_EXPIRED, SCOPE_MISMATCH,
        PROTOCOL_ERROR, PRODUCER_REPLACED, BACKPRESSURE, SESSION_NOT_FOUND,
        PING_TIMEOUT, NETWORK_ERROR, CLOSED
    }

    public interface StatusListener {
        void onStatus(Status status, String detail);
    }

    public static final class Session {
        public final String url;
        public final String producerToken;
        public final long expiresAtMs;

        public Session(String url, String producerToken, long expiresAtMs) {
            this.url = Objects.requireNonNull(url, "url");
            this.producerToken = Objects.requireNonNull(producerToken, "producerToken");
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final WebSocket.Factory webSockets;
    private final Runnable requestKeyframe;
    private final StatusListener statuses;
    private final LongSupplier clock;
    private final BoundedFrameQueue queue;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final boolean ownsFactory;
    private long generation;
    private long sequence;
    private WebSocket socket;
    private ScheduledFuture<?> expiry;
    private boolean connected;
    private boolean activeCredentials;
    private boolean configSent;
    private boolean closed;

    public VideoRelayClient(WebSocket.Factory webSockets, Runnable requestKeyframe,
            StatusListener statuses, LongSupplier clock) {
        this(webSockets, requestKeyframe, statuses, clock, false);
    }

    private VideoRelayClient(WebSocket.Factory webSockets, Runnable requestKeyframe,
            StatusListener statuses, LongSupplier clock, boolean ownsFactory) {
        this.webSockets = Objects.requireNonNull(webSockets, "webSockets");
        this.requestKeyframe = Objects.requireNonNull(requestKeyframe, "requestKeyframe");
        this.statuses = Objects.requireNonNull(statuses, "statuses");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queue = new BoundedFrameQueue(requestKeyframe);
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CameraLiveProducer");
            thread.setDaemon(true);
            return thread;
        });
        this.ownsFactory = ownsFactory;
    }

    public static VideoRelayClient createDefault(Runnable requestKeyframe,
            StatusListener statuses, LongSupplier clock) {
        java.util.concurrent.ExecutorService dispatcherThread =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "CameraLiveWebSocket");
                    thread.setDaemon(true);
                    return thread;
                });
        Dispatcher dispatcher = new Dispatcher(dispatcherThread);
        OkHttpClient client = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .build();
        return new VideoRelayClient(client, requestKeyframe, statuses, clock, true);
    }

    public synchronized void start(Session session) {
        if (closed) throw new IllegalStateException("Live client is closed");
        validateSession(session, clock.getAsLong());
        endCurrent(true, Status.CLOSED, "replaced");
        long currentGeneration = ++generation;
        sequence = 0;
        activeCredentials = true;
        configSent = false;
        queue.clear();
        statuses.onStatus(Status.CONNECTING, null);
        Request request = new Request.Builder().url(session.url)
                .header("Authorization", "Bearer " + session.producerToken).build();
        socket = webSockets.newWebSocket(request, listener(currentGeneration));
        long delay = Math.max(1, session.expiresAtMs - clock.getAsLong());
        expiry = worker.schedule(() -> expire(currentGeneration), delay, TimeUnit.MILLISECONDS);
    }

    @Override public void onCodecConfig(byte[] sps, byte[] pps) {
        byte[] message;
        synchronized (this) {
            if (!activeCredentials || closed) return;
            message = bytes(H264WireCodec.encodeConfig(nextSequence(), 0,
                    stripStartCode(sps), stripStartCode(pps)));
            configSent = false;
        }
        queue.offerConfig(message);
        scheduleDrain();
    }

    @Override public void write(EncodedAccessUnit unit) {
        Objects.requireNonNull(unit, "unit");
        byte[] message;
        synchronized (this) {
            if (!activeCredentials || closed) return;
            message = bytes(H264WireCodec.encodeAccessUnit(nextSequence(),
                    unit.presentationTimeUs, unit.keyframe, nalUnits(unit.data())));
        }
        queue.offerAccessUnit(message, unit.keyframe);
        scheduleDrain();
    }

    public void resetCodec() {
        synchronized (this) { configSent = false; }
        queue.clear();
    }

    public synchronized boolean hasActiveCredentials() { return activeCredentials; }
    public int queuedMessages() { return queue.size(); }

    private WebSocketListener listener(long expectedGeneration) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                synchronized (VideoRelayClient.this) {
                    if (expectedGeneration != generation || !activeCredentials) {
                        webSocket.close(NORMAL_CLOSE, "stale session");
                        return;
                    }
                    socket = webSocket;
                    connected = true;
                }
                statuses.onStatus(Status.CONNECTED, null);
                scheduleDrain();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if ("{\"type\":\"request_keyframe\"}".equals(text)) requestKeyframe.run();
                else webSocket.close(4004, "unsupported control message");
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                finish(expectedGeneration, status(code), reason);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
                Status status = isPingTimeout(failure) ? Status.PING_TIMEOUT : Status.NETWORK_ERROR;
                finish(expectedGeneration, status, failure == null ? null : failure.getClass().getSimpleName());
            }
        };
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) worker.execute(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                WebSocket destination;
                synchronized (this) {
                    if (!connected || !activeCredentials) return;
                    destination = socket;
                }
                BoundedFrameQueue.Message message = queue.poll();
                if (message == null) return;
                synchronized (this) {
                    if (!message.config && !configSent) {
                        requestKeyframe.run();
                        continue;
                    }
                }
                if (!destination.send(ByteString.of(message.bytes()))) {
                    finishCurrent(Status.NETWORK_ERROR, "send rejected");
                    return;
                }
                if (message.config) synchronized (this) { configSent = true; }
            }
        } finally {
            drainScheduled.set(false);
            if (queue.size() > 0) scheduleDrain();
        }
    }

    private void expire(long expectedGeneration) {
        synchronized (this) {
            if (expectedGeneration != generation || !activeCredentials) return;
        }
        finish(expectedGeneration, Status.TOKEN_EXPIRED, "expired");
    }

    private void finish(long expectedGeneration, Status status, String detail) {
        synchronized (this) {
            if (expectedGeneration != generation || !activeCredentials) return;
            endCurrent(true, status, detail);
        }
        statuses.onStatus(status, detail);
    }

    private void finishCurrent(Status status, String detail) {
        synchronized (this) {
            if (!activeCredentials) return;
            endCurrent(true, status, detail);
        }
        statuses.onStatus(status, detail);
    }

    private void endCurrent(boolean sendClose, Status status, String detail) {
        WebSocket old = socket;
        socket = null;
        connected = false;
        activeCredentials = false;
        configSent = false;
        queue.clear();
        if (expiry != null) expiry.cancel(false);
        expiry = null;
        if (sendClose && old != null) old.close(NORMAL_CLOSE, detail == null ? "session ended" : detail);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        endCurrent(true, Status.CLOSED, "client closed");
        worker.shutdownNow();
        if (ownsFactory && webSockets instanceof OkHttpClient) {
            OkHttpClient client = (OkHttpClient) webSockets;
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private synchronized long nextSequence() {
        long result = sequence;
        sequence = (sequence + 1) & 0xffff_ffffL;
        return result;
    }

    static void validateSession(Session session, long nowMs) {
        URI uri;
        try { uri = URI.create(session.url); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid live URL", invalid); }
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Live URL must be WSS without user info or fragment");
        }
        if (session.producerToken.length() < 32 || session.producerToken.length() > 512
                || session.url.contains(session.producerToken)) {
            throw new IllegalArgumentException("Invalid producer token");
        }
        if (session.expiresAtMs <= nowMs || session.expiresAtMs - nowMs > 30 * 60_000L) {
            throw new IllegalArgumentException("Expired live session");
        }
    }

    private static Status status(int code) {
        switch (code) {
            case 4000: return Status.SESSION_ENDED;
            case 4001: return Status.AUTH_FAILED;
            case 4002: return Status.TOKEN_EXPIRED;
            case 4003: return Status.SCOPE_MISMATCH;
            case 4004: return Status.PROTOCOL_ERROR;
            case 4005: case 4006: return Status.PRODUCER_REPLACED;
            case 4007: return Status.BACKPRESSURE;
            case 4008: return Status.SESSION_NOT_FOUND;
            default: return Status.CLOSED;
        }
    }

    private static boolean isPingTimeout(Throwable failure) {
        if (failure instanceof SocketTimeoutException) return true;
        String message = failure == null || failure.getMessage() == null ? ""
                : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("ping") || message.contains("pong");
    }

    private static byte[] stripStartCode(byte[] source) {
        Objects.requireNonNull(source, "NAL");
        int offset = startCodeLength(source, 0);
        byte[] result = Arrays.copyOfRange(source, offset, source.length);
        if (result.length == 0) throw new IllegalArgumentException("Empty NAL");
        return result;
    }

    private static byte[][] nalUnits(ByteBuffer source) {
        byte[] data = new byte[source.remaining()];
        source.duplicate().get(data);
        List<byte[]> annexB = splitAnnexB(data);
        if (!annexB.isEmpty()) return annexB.toArray(new byte[0][]);
        List<byte[]> avcc = splitAvcc(data);
        if (!avcc.isEmpty()) return avcc.toArray(new byte[0][]);
        return new byte[][] { data };
    }

    private static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        int start = startCodeLength(data, 0);
        if (start == 0) return result;
        int nalStart = start;
        for (int index = nalStart; index < data.length; index++) {
            int prefix = startCodeLength(data, index);
            if (prefix > 0) {
                if (index > nalStart) result.add(Arrays.copyOfRange(data, nalStart, index));
                nalStart = index + prefix;
                index = nalStart - 1;
            }
        }
        if (nalStart < data.length) result.add(Arrays.copyOfRange(data, nalStart, data.length));
        return result;
    }

    private static List<byte[]> splitAvcc(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 4) return new ArrayList<>();
            int size = buffer.getInt();
            if (size <= 0 || size > buffer.remaining()) return new ArrayList<>();
            byte[] nal = new byte[size];
            buffer.get(nal);
            result.add(nal);
        }
        return result;
    }

    private static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 <= data.length && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 1) return 3;
        if (offset + 4 <= data.length && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 0 && data[offset + 3] == 1) return 4;
        return 0;
    }

    private static byte[] bytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }
}
