package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LocalShareServerTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void pairingLocksAfterFiveFailuresAndExpires() throws Exception {
        LocalShareServer.Session session = session(1_000);
        for (int i = 0; i < 4; i++) assertEquals(401, session.pair("10.0.0.2", "000000", 1_001).status);
        assertEquals(423, session.pair("10.0.0.2", "000000", 1_001).status);
        assertEquals(423, session.pair("10.0.0.2", "123456", 1_001).status);
        assertEquals(410, session.list("10.0.0.2", "x", 601_001).status);
    }

    @Test public void cookieIs128BitAndBoundToClient() throws Exception {
        LocalShareServer.Session session = session(1_000);
        LocalShareServer.Response paired = session.pair("10.0.0.2", "123456", 1_001);
        assertEquals(200, paired.status);
        assertEquals(32, paired.cookie.length());
        assertEquals(200, session.list("10.0.0.2", paired.cookie, 1_002).status);
        assertEquals(403, session.list("10.0.0.3", paired.cookie, 1_002).status);
    }

    @Test public void servesCompletedCanonicalFileAndBoundedRanges() throws Exception {
        LocalShareServer.Session session = session(1_000);
        String cookie = session.pair("10.0.0.2", "123456", 1_001).cookie;
        LocalShareServer.Response response = session.download(
                "10.0.0.2", cookie, "7", "1", "bytes=1-3", 1_002);
        assertEquals(206, response.status);
        assertEquals("bcd", new String(response.body, StandardCharsets.UTF_8));
        assertEquals(416, session.download(
                "10.0.0.2", cookie, "7", "1", "bytes=9-10", 1_002).status);
        assertEquals(400, session.download(
                "10.0.0.2", cookie, "../7", "1", null, 1_002).status);
        assertEquals(400, session.download(
                "10.0.0.2", cookie, "7", "../../1", null, 1_002).status);
    }

    @Test public void rejectsActivePartialOversizedAndPublicBindings() throws Exception {
        LocalShareServer.Session session = session(1_000);
        String cookie = session.pair("10.0.0.2", "123456", 1_001).cookie;
        assertEquals(404, session.download(
                "10.0.0.2", cookie, "8", "1", null, 1_002).status);
        assertEquals(400, LocalShareServer.parseRequest(new byte[20_000]).error.status);
        assertFalse(LocalShareServer.isPrivateWifiAddress("0.0.0.0"));
        assertFalse(LocalShareServer.isPrivateWifiAddress("8.8.8.8"));
        assertTrue(LocalShareServer.isPrivateWifiAddress("192.168.1.2"));
    }

    @Test public void closeInvalidatesSessionAndClosesSocket() throws Exception {
        LocalShareServer.Session session = session(1_000);
        session.close();
        assertTrue(session.isClosed());
        assertEquals(410, session.pair("10.0.0.2", "123456", 1_001).status);
    }

    private LocalShareServer.Session session(long now) throws Exception {
        File root = folder.getRoot();
        VideoCatalog catalog = new VideoCatalog(root);
        catalog.beginTrip("7", 1, VideoCatalog.Source.MANUAL);
        File partial = new File(root, "trip-video/7/current.partial");
        Files.write(partial.toPath(), "abcdef".getBytes(StandardCharsets.UTF_8));
        catalog.completeSegment("7", new VideoCatalog.SegmentMetadata(
                1, 2, 1_000, 6,
                "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721",
                new VideoCatalog.Codec("video/avc", 1760, 720, 15, 4_000_000)));
        catalog.completeTrip("7", 2);
        catalog.beginTrip("8", 3, VideoCatalog.Source.AUTOMATIC);
        return new LocalShareServer.Session(root, catalog, "123456", now,
                bytes -> "00112233445566778899aabbccddeeff");
    }
}
