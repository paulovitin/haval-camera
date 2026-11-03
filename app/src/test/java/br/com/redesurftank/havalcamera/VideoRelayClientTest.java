package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.Test;

public final class VideoRelayClientTest {
    @Test public void producerUsesBearerHeaderAndNeverPlacesTokenInUrl() {
        Fixture fixture = new Fixture();

        fixture.client.start(fixture.session("secret-producer-token-1234567890"));

        Request request = fixture.factory.requests.get(0);
        assertEquals("Bearer secret-producer-token-1234567890", request.header("Authorization"));
        // OkHttp represents a WSS handshake as its equivalent HTTPS request URL.
        assertEquals("https://bridge.example/ws/video/live/car?session=session-1",
                request.url().toString());
        assertFalse(request.url().toString().contains("secret-producer"));
        fixture.client.close();
    }

    @Test public void validatedSessionExposesOnlyItsSessionId() {
        Fixture fixture = new Fixture();
        VideoRelayClient.Session session = fixture.session("a".repeat(32));

        assertEquals("session-1", session.sessionId);
        fixture.client.close();
    }

    @Test public void onlyWssUrlsWithoutUserInfoOrFragmentsAreAccepted() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.client.start(
                new VideoRelayClient.Session("ws://bridge.example/live?session=1", "x".repeat(32),
                        fixture.now.get() + 60_000)));
        assertThrows(IllegalArgumentException.class, () -> fixture.client.start(
                new VideoRelayClient.Session("wss://user@bridge.example/live?session=1", "x".repeat(32),
                        fixture.now.get() + 60_000)));
        assertEquals(0, fixture.factory.requests.size());
        fixture.client.close();
    }

    @Test public void producerUrlAllowsOnlyTheSessionQueryAndNeverQueryAuthentication() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.client.start(
                new VideoRelayClient.Session(
                        "wss://bridge.example/live?session=1&token=query-secret", "x".repeat(32),
                        fixture.now.get() + 60_000)));
        assertThrows(IllegalArgumentException.class, () -> fixture.client.start(
                new VideoRelayClient.Session("wss://bridge.example/live", "x".repeat(32),
                        fixture.now.get() + 60_000)));

        assertEquals(0, fixture.factory.requests.size());
        fixture.client.close();
    }

    @Test public void sendsConfigBeforeFirstAccessUnit() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.client.onCodecConfig(bytes(0, 0, 0, 1, 0x67, 0x42),
                bytes(0, 0, 0, 1, 0x68, 0x11));
        fixture.client.write(new EncodedAccessUnit(bytes(0x65, 0x55), 66_667, true, false));

        fixture.factory.open(0);
        await(() -> fixture.factory.sockets.get(0).binary.size() == 2);

        H264WireCodec.Frame config = H264WireCodec.decode(
                fixture.factory.sockets.get(0).binary.get(0).asByteBuffer());
        H264WireCodec.Frame keyframe = H264WireCodec.decode(
                fixture.factory.sockets.get(0).binary.get(1).asByteBuffer());
        assertEquals(H264WireCodec.Type.CONFIG, config.type);
        assertEquals(H264WireCodec.Type.ACCESS_UNIT, keyframe.type);
        assertTrue(keyframe.keyframe);
        fixture.client.close();
    }

    @Test public void failureNeverRetriesConsumedCredentialsAndFreshStartUsesFreshToken() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.factory.open(0);

        fixture.factory.fail(0, new SocketTimeoutException("sent ping but did not receive pong"));
        await(() -> !fixture.client.hasActiveCredentials());
        Thread.sleep(30);
        assertEquals(1, fixture.factory.requests.size());
        assertEquals(VideoRelayClient.Status.PING_TIMEOUT, fixture.statuses.get(fixture.statuses.size() - 1));

        fixture.client.start(new VideoRelayClient.Session(
                "wss://bridge.example/ws/video/live/car?session=session-2", "b".repeat(32),
                fixture.now.get() + 60_000));
        assertEquals("Bearer " + "b".repeat(32), fixture.factory.requests.get(1).header("Authorization"));
        fixture.client.close();
    }

    @Test public void serverKeyframeControlUsesApprovedRelayFormat() {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.factory.open(0);

        fixture.factory.text(0, "{\"type\":\"request_keyframe\"}");

        assertEquals(1, fixture.keyframeRequests.get());
        fixture.client.close();
    }

    @Test public void websocketQueueAndLocalQueueShareOneMibBound() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.factory.open(0);
        fixture.client.onCodecConfig(bytes(0x67), bytes(0x68));
        fixture.client.write(new EncodedAccessUnit(bytes(0x65), 1, true, false));
        await(() -> fixture.factory.sockets.get(0).binary.size() == 2);
        int sent = fixture.factory.sockets.get(0).binary.size();
        int requests = fixture.keyframeRequests.get();
        fixture.factory.sockets.get(0).queuedBytes = BoundedFrameQueue.MAX_BYTES - 1;

        fixture.client.write(new EncodedAccessUnit(bytes(0x41, 0x01), 2, false, false));
        await(() -> fixture.keyframeRequests.get() > requests);

        assertEquals(sent, fixture.factory.sockets.get(0).binary.size());
        assertTrue(fixture.client.isRecovering());
        fixture.client.close();
    }

    @Test public void interleavedDrainAndOfferDoNotSendWhileTransportIsBusy() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.client.onCodecConfig(bytes(0x67), bytes(0x68));
        FakeSocket socket = fixture.factory.sockets.get(0);
        socket.queuedBytes = 1;
        socket.queueSizeEntered = new CountDownLatch(1);
        socket.releaseQueueSize = new CountDownLatch(1);

        fixture.factory.open(0);
        assertTrue(socket.queueSizeEntered.await(1, TimeUnit.SECONDS));
        Thread offer = new Thread(() -> fixture.client.write(
                new EncodedAccessUnit(bytes(0x65), 1, true, false)));
        offer.start();
        socket.releaseQueueSize.countDown();
        offer.join(1_000);

        assertFalse(offer.isAlive());
        assertEquals(0, socket.binary.size());
        assertEquals(2, fixture.client.queuedMessages());
        fixture.client.close();
    }

    @Test public void canonicalCloseCodesMapToStatusAndClearCredentials() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.factory.open(0);

        fixture.factory.closed(0, 4002, "token expired");
        await(() -> !fixture.client.hasActiveCredentials());

        assertEquals(VideoRelayClient.Status.TOKEN_EXPIRED,
                fixture.statuses.get(fixture.statuses.size() - 1));
        assertEquals(0, fixture.client.queuedMessages());
        fixture.client.close();
    }

    @Test public void malformedCodecConfigEndsOnlyTheLiveSession() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.start(fixture.session("a".repeat(32)));
        fixture.factory.open(0);

        fixture.client.onCodecConfig(bytes(0, 0, 0, 1), bytes(0, 0, 0, 1, 0x68));
        await(() -> !fixture.client.hasActiveCredentials());

        assertEquals(VideoRelayClient.Status.PROTOCOL_ERROR,
                fixture.statuses.get(fixture.statuses.size() - 1));
        fixture.client.close();
    }

    @Test public void malformedAvccAndInvalidRawNalCloseOnlyLive() throws Exception {
        Fixture malformedAvcc = new Fixture();
        malformedAvcc.client.start(malformedAvcc.session("a".repeat(32)));
        malformedAvcc.factory.open(0);
        malformedAvcc.client.onCodecConfig(bytes(0x67), bytes(0x68));
        malformedAvcc.client.write(new EncodedAccessUnit(
                bytes(0, 0, 0, 5, 0x65), 1, true, false));
        await(() -> !malformedAvcc.client.hasActiveCredentials());
        assertEquals(VideoRelayClient.Status.PROTOCOL_ERROR,
                malformedAvcc.statuses.get(malformedAvcc.statuses.size() - 1));
        malformedAvcc.client.close();

        Fixture invalidRaw = new Fixture();
        invalidRaw.client.start(invalidRaw.session("b".repeat(32)));
        invalidRaw.factory.open(0);
        invalidRaw.client.onCodecConfig(bytes(0x67), bytes(0x68));
        invalidRaw.client.write(new EncodedAccessUnit(bytes(0x00, 0x11), 1, false, false));
        await(() -> !invalidRaw.client.hasActiveCredentials());
        assertEquals(VideoRelayClient.Status.PROTOCOL_ERROR,
                invalidRaw.statuses.get(invalidRaw.statuses.size() - 1));
        invalidRaw.client.close();
    }

    @Test public void alreadyExpiredCredentialsFailWithoutOpeningOrRetrying() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.client.start(
                new VideoRelayClient.Session("wss://bridge.example/live?session=1", "a".repeat(32),
                        fixture.now.get())));

        assertEquals(0, fixture.factory.requests.size());
        assertFalse(fixture.client.hasActiveCredentials());
        fixture.client.close();
    }

    @Test public void liveAndTransferHaveSeparateSocketsAndQueuePressure() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("transfer-live-test");
        Fixture live = new Fixture();
        try (RecordingTransferTest.Fixture transfer = new RecordingTransferTest.Fixture(root.toFile(), 3)) {
            transfer.start(true); transfer.open(); transfer.socket().queued = 1048576;
            transfer.resume(0); transfer.quiet(0);
            live.client.start(live.session("l".repeat(32))); live.factory.open(0);
            live.client.onCodecConfig(bytes(0x67), bytes(0x68));
            live.client.write(new EncodedAccessUnit(bytes(0x65), 1, true, false));
            await(() -> live.factory.sockets.get(0).binary.size() == 2);
            assertEquals(1, transfer.factory.requests.size());
            transfer.factory.fail(0);
            assertTrue(live.client.hasActiveCredentials());
            assertEquals(H264WireCodec.Type.CONFIG, H264WireCodec.decode(
                    live.factory.sockets.get(0).binary.get(0).asByteBuffer()).type);
        } finally {
            live.client.close();
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(
                        java.util.Comparator.reverseOrder())::iterator) java.nio.file.Files.delete(path);
            }
        }
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        final FakeFactory factory = new FakeFactory();
        final AtomicInteger keyframeRequests = new AtomicInteger();
        final List<VideoRelayClient.Status> statuses = new ArrayList<>();
        final VideoRelayClient client = new VideoRelayClient(
                factory, keyframeRequests::incrementAndGet,
                (status, detail) -> statuses.add(status), now::get);

        VideoRelayClient.Session session(String token) {
            return new VideoRelayClient.Session(
                    "wss://bridge.example/ws/video/live/car?session=session-1", token,
                    now.get() + 60_000);
        }
    }

    private static final class FakeFactory implements WebSocket.Factory {
        final List<Request> requests = new ArrayList<>();
        final List<WebSocketListener> listeners = new ArrayList<>();
        final List<FakeSocket> sockets = new ArrayList<>();

        @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
            requests.add(request);
            listeners.add(listener);
            FakeSocket socket = new FakeSocket(request);
            sockets.add(socket);
            return socket;
        }

        void open(int index) { listeners.get(index).onOpen(sockets.get(index), (Response) null); }
        void text(int index, String value) { listeners.get(index).onMessage(sockets.get(index), value); }
        void fail(int index, Throwable failure) {
            listeners.get(index).onFailure(sockets.get(index), failure, null);
        }
        void closed(int index, int code, String reason) {
            listeners.get(index).onClosed(sockets.get(index), code, reason);
        }
    }

    private static final class FakeSocket implements WebSocket {
        final Request request;
        final List<ByteString> binary = new ArrayList<>();
        boolean canceled;
        boolean closed;
        volatile long queuedBytes;
        volatile CountDownLatch queueSizeEntered;
        volatile CountDownLatch releaseQueueSize;

        FakeSocket(Request request) { this.request = request; }
        @Override public Request request() { return request; }
        @Override public long queueSize() {
            CountDownLatch entered = queueSizeEntered;
            CountDownLatch release = releaseQueueSize;
            if (entered != null) entered.countDown();
            if (release != null) {
                try { release.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            return queuedBytes;
        }
        @Override public boolean send(String text) { return !closed; }
        @Override public boolean send(ByteString bytes) { binary.add(bytes); return !closed; }
        @Override public boolean close(int code, String reason) { closed = true; return true; }
        @Override public void cancel() { canceled = true; closed = true; }
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (!check.done() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(check.done());
    }

    private interface Check { boolean done(); }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }
}
