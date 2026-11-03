package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class LiveSessionPolicyTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final String QUERY = "/ws/video/lan/car?session=session-1";

    @Test public void acceptsRemoteWssAndOnlyLiteralLoopbackWs() {
        for (String url : new String[] {"wss://bridge.example/live?session=s",
                "wss://bridge.example:443/live?session=s"}) {
            assertEquals("REMOTE_WSS", validate(url, NOW + 1, NOW));
        }
        for (String host : new String[] {"127.0.0.1", "[::1]"}) {
            for (int port : new int[] {1, 8765, 65535}) {
                assertEquals("LOOPBACK_WS", validate("ws://" + host + ":" + port + QUERY,
                        NOW + 30 * 60_000L, NOW));
            }
        }
    }

    @Test public void rejectsNamesLanPublicAndNoncanonicalLoopbackHosts() {
        for (String host : new String[] {"localhost", "LOCALHOST", "localhost.",
                "127.0.0.1.example", "192.168.1.2", "10.0.0.1", "172.16.0.1",
                "8.8.8.8", "0.0.0.0", "127.0.0.2", "127.1", "2130706433",
                "0177.0.0.1", "0x7f000001", "[0:0:0:0:0:0:0:1]",
                "[::ffff:127.0.0.1]", "[::1%25lo0]", "[::]"}) {
            reject("ws://" + host + ":8765" + QUERY);
        }
    }

    @Test public void rejectsMissingDefaultZeroInvalidAndAmbiguousWsPorts() {
        for (String host : new String[] {"127.0.0.1", "[::1]"}) {
            for (String port : new String[] {"", ":", ":0", ":80", ":-1", ":65536",
                    ":9999999999999999999", ":008765", ":+8765", ":port"}) {
                reject("ws://" + host + port + QUERY);
            }
        }
    }

    @Test public void rejectsUserInfoFragmentsAndNonWebsocketSchemes() {
        for (String url : new String[] {"ws://user@127.0.0.1:8765" + QUERY,
                "ws://@127.0.0.1:8765" + QUERY,
                "wss://user:pass@bridge.example/live?session=s",
                "ws://127.0.0.1:8765" + QUERY + "#", "ws://127.0.0.1:8765" + QUERY + "#part",
                "wss://bridge.example/live?session=s#part", "http://127.0.0.1:8765" + QUERY,
                "https://bridge.example/live?session=s", "ws:127.0.0.1:8765" + QUERY,
                "ws:///live?session=s", " wss://bridge.example/live?session=s"}) reject(url);
        reject(null);
    }

    @Test public void requiresOneUnencodedSessionQueryWithoutAuthentication() {
        for (String schemeHost : new String[] {"ws://127.0.0.1:8765", "wss://bridge.example"}) {
            for (String query : new String[] {"", "?", "?session=", "?session=a&session=b",
                    "?session=a&", "?session=a&token=secret", "?token=secret&session=a",
                    "?session=a;token=secret", "?session=a?token=secret", "?Session=a",
                    "?%73ession=a", "?session=%61", "?session=a+b", "?session=a/b"}) {
                reject(schemeHost + "/live" + query);
            }
        }
    }

    @Test public void rejectsExpiredFarFutureAndOverflowingExpiry() {
        for (long expires : new long[] {NOW - 1, NOW, NOW + 30 * 60_000L + 1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> validate("ws://127.0.0.1:8765" + QUERY, expires, NOW));
        }
        assertThrows(IllegalArgumentException.class,
                () -> validate("wss://bridge.example/live?session=s", Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test public void exportedServiceRejectsUnprotectedCallersAndHasNoCleartextOverride() throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Document manifest = factory.newDocumentBuilder().parse(
                sourceRoot().resolve("AndroidManifest.xml").toFile());
        String android = "http://schemas.android.com/apk/res/android";
        boolean signaturePermission = false;
        org.w3c.dom.NodeList permissions = manifest.getElementsByTagName("permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            org.w3c.dom.Element permission = (org.w3c.dom.Element) permissions.item(i);
            if (VideoContract.CONTROL_PERMISSION.equals(permission.getAttributeNS(android, "name"))) {
                assertEquals("signature", permission.getAttributeNS(android, "protectionLevel"));
                signaturePermission = true;
            }
        }
        assertTrue(signaturePermission);
        boolean guarded = false;
        org.w3c.dom.NodeList services = manifest.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            org.w3c.dom.Element service = (org.w3c.dom.Element) services.item(i);
            if (".TripVideoService".equals(service.getAttributeNS(android, "name"))) {
                assertEquals("true", service.getAttributeNS(android, "exported"));
                assertEquals(VideoContract.CONTROL_PERMISSION, service.getAttributeNS(android, "permission"));
                guarded = true;
            }
        }
        assertTrue(guarded);
        org.w3c.dom.Element app = (org.w3c.dom.Element) manifest.getElementsByTagName("application").item(0);
        assertTrue(!app.hasAttributeNS(android, "usesCleartextTraffic"));
        assertTrue(!app.hasAttributeNS(android, "networkSecurityConfig"));
    }

    @Test public void serviceChecksIpcSessionIdentityAndLivePolicyBeforeJournalMutation() throws Exception {
        String service = Files.readString(sourceRoot().resolve(
                "java/br/com/redesurftank/havalcamera/TripVideoService.java"));
        int preflight = service.indexOf("? requireLiveSession(intent) : null");
        assertTrue(preflight >= 0 && preflight < service.indexOf("journal.apply", preflight));
        String validation = service.substring(service.indexOf("private VideoRelayClient.Session requireLiveSession"),
                service.indexOf("private void attachLive"));
        assertTrue(validation.contains("VideoContract.EXTRA_SESSION_ID"));
        assertTrue(validation.contains("session.sessionId.equals("));
        assertTrue(validation.contains("VideoRelayClient.validateLiveSession("));
    }

    private static Path sourceRoot() {
        return Files.exists(Path.of("src/main/AndroidManifest.xml"))
                ? Path.of("src/main") : Path.of("app/src/main");
    }

    // Reflection makes the tests-only RED runnable against the unmodified base.
    private static String validate(String url, long expires, long now) {
        try {
            return LiveSessionPolicy.class.getDeclaredMethod("validate", String.class, long.class, long.class)
                    .invoke(null, url, expires, now).toString();
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException) throw (RuntimeException) failure.getCause();
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError("Live URL scope policy is missing", missing);
        }
    }

    private static void reject(String url) {
        assertThrows(String.valueOf(url), IllegalArgumentException.class,
                () -> validate(url, NOW + 60_000, NOW));
    }

    @Test public void disabledRemoteLiveRejectsBeforeJournalMutation() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.settings(1, true, false, false, true, null));
        CommandJournal journal = new CommandJournal(new CommandJournalTest.MemoryPreferences());
        LiveSessionPolicy policy = new LiveSessionPolicy();

        assertThrows(IllegalStateException.class,
                () -> policy.requireStartAllowed(reducer.state()));

        assertEquals(0, journal.revision());
        assertNull(journal.activeTripId());
    }

    @Test public void startRequiresConfirmedLogicalViewer() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        LiveSessionPolicy policy = new LiveSessionPolicy();

        assertThrows(IllegalStateException.class,
                () -> policy.confirmStarted("session-a", reducer.state()));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(1, 1_000));
        policy.confirmStarted("session-a", reducer.state());

        assertEquals("session-a", policy.activeSessionId());
    }

    @Test public void staleStopCannotCloseNewerSession() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.viewerConnected(1, 1_000));
        LiveSessionPolicy policy = new LiveSessionPolicy();
        policy.confirmStarted("session-b", reducer.state());

        assertThrows(IllegalArgumentException.class,
                () -> policy.requireMatchingStop("session-a"));
        assertEquals("session-b", policy.activeSessionId());

        policy.requireMatchingStop("session-b");
        policy.clear();
        assertNull(policy.activeSessionId());
    }

    @Test public void servicePreflightsLiveCommandsBeforeJournalApplication() throws Exception {
        Path sourceRoot = Files.exists(Path.of("src/main/AndroidManifest.xml"))
                ? Path.of("src/main") : Path.of("app/src/main");
        String service = new String(Files.readAllBytes(sourceRoot.resolve(
                "java/br/com/redesurftank/havalcamera/TripVideoService.java")),
                StandardCharsets.UTF_8);

        int startGuard = service.indexOf("liveSessionPolicy.requireStartAllowed");
        int stopGuard = service.indexOf("liveSessionPolicy.requireMatchingStop");
        int journal = service.indexOf("journal.apply", startGuard);
        assertTrue(startGuard >= 0 && stopGuard >= 0);
        assertTrue(startGuard < journal);
        assertTrue(stopGuard < journal);
        assertTrue(service.contains("liveSessionPolicy.confirmStarted(requestedLive.sessionId"));
    }
}
