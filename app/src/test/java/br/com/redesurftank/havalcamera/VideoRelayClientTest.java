package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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
        assertEquals("wss://bridge.example/ws/video/live/car?session=session-1",
                request.url().toString());
        assertFalse(request.url().toString().contains("secret-producer"));
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

    @Test public void alreadyExpiredCredentialsFailWithoutOpeningOrRetrying() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.client.start(
                new VideoRelayClient.Session("wss://bridge.example/live?session=1", "a".repeat(32),
                        fixture.now.get())));

        assertEquals(0, fixture.factory.requests.size());
        assertFalse(fixture.client.hasActiveCredentials());
        fixture.client.close();
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

        FakeSocket(Request request) { this.request = request; }
        @Override public Request request() { return request; }
        @Override public long queueSize() { return 0; }
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
