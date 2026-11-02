package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class VideoCatalogTest {
    private static final String SHA_ONE =
            "7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed";
    private static final String SHA_TWO =
            "3fc4ccfe745870e2c0d99f71f30ff0656c8dedd41cc1d7d3d376b0dbe685e2f3";
    private static final VideoCatalog.Codec AVC =
            new VideoCatalog.Codec("video/avc", 1760, 720, 15, 4_000_000);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void completedSegmentsAreOrderedAndKeepHashesAndSource() throws Exception {
        VideoCatalog catalog = new VideoCatalog(temporaryFolder.getRoot());
        catalog.beginTrip("1700000000000", 1_000, VideoCatalog.Source.MANUAL);

        writePartial("1700000000000", "one");
        catalog.completeSegment("1700000000000",
                new VideoCatalog.SegmentMetadata(1_000, 61_000, 60_000_000, 3, SHA_ONE, AVC));
        writePartial("1700000000000", "two");
        catalog.completeSegment("1700000000000",
                new VideoCatalog.SegmentMetadata(61_000, 91_000, 30_000_000, 3, SHA_TWO, AVC));
        catalog.completeTrip("1700000000000", 91_000);

        VideoCatalog.Trip trip = catalog.completedSnapshot().get(0);
        assertEquals(1700000000000L, trip.tripId);
        assertEquals(VideoCatalog.Source.MANUAL, trip.source);
        assertTrue(trip.completed);
        assertEquals(2, trip.segments.size());
        assertEquals("000001.mp4", trip.segments.get(0).fileName);
        assertEquals(SHA_ONE, trip.segments.get(0).sha256);
        assertEquals("000002.mp4", trip.segments.get(1).fileName);
        assertEquals(SHA_TWO, trip.segments.get(1).sha256);
        assertTrue(new File(tripDirectory("1700000000000"), "000001.mp4").isFile());
        assertTrue(new File(tripDirectory("1700000000000"), "000002.mp4").isFile());
    }

    @Test
    public void activeTripsNeverEnterCompletedSnapshot() throws Exception {
        VideoCatalog catalog = new VideoCatalog(temporaryFolder.getRoot());
        catalog.beginTrip("42", 1_000, VideoCatalog.Source.AUTOMATIC);
        writePartial("42", "one");
        catalog.completeSegment("42",
                new VideoCatalog.SegmentMetadata(1_000, 2_000, 1_000_000, 3, SHA_ONE, AVC));

        assertTrue(catalog.completedSnapshot().isEmpty());
        assertEquals(1, catalog.localSnapshot().size());
        assertFalse(catalog.localSnapshot().get(0).completed);
        assertEquals(VideoCatalog.Source.AUTOMATIC, catalog.localSnapshot().get(0).source);
    }

    @Test
    public void completingTripAtomicallyReplacesIndex() throws Exception {
        VideoCatalog catalog = new VideoCatalog(temporaryFolder.getRoot());
        catalog.beginTrip("7", 1_000, VideoCatalog.Source.AUTOMATIC);
        File index = new File(temporaryFolder.getRoot(), "trip-video/index.json");
        Files.write(index.toPath(), "stale-index".getBytes(StandardCharsets.UTF_8));

        catalog.completeTrip("7", 2_000);

        String stored = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
        assertTrue(stored.contains("\"tripId\":7"));
        assertFalse(stored.contains("stale-index"));
        assertFalse(new File(index.getParentFile(), "index.json.tmp").exists());
    }

    @Test
    public void recoveryDeletesPartialButPreservesCompletedMp4() throws Exception {
        VideoCatalog first = new VideoCatalog(temporaryFolder.getRoot());
        first.beginTrip("99", 1_000, VideoCatalog.Source.MANUAL);
        writePartial("99", "one");
        first.completeSegment("99",
                new VideoCatalog.SegmentMetadata(1_000, 2_000, 1_000_000, 3, SHA_ONE, AVC));
        writePartial("99", "unfinished");

        VideoCatalog recovered = new VideoCatalog(temporaryFolder.getRoot());
        recovered.recover();

        assertFalse(new File(tripDirectory("99"), "current.partial").exists());
        assertTrue(new File(tripDirectory("99"), "000001.mp4").isFile());
        assertEquals(1, recovered.localSnapshot().size());
        assertEquals(VideoCatalog.Source.MANUAL, recovered.localSnapshot().get(0).source);
        assertEquals(SHA_ONE, recovered.localSnapshot().get(0).segments.get(0).sha256);
        assertTrue(recovered.completedSnapshot().isEmpty());
    }

    @Test
    public void completedTripSurvivesRecoveryAndRemainsDownloadable() throws Exception {
        VideoCatalog first = new VideoCatalog(temporaryFolder.getRoot());
        first.beginTrip("100", 1_000, VideoCatalog.Source.AUTOMATIC);
        writePartial("100", "one");
        first.completeSegment("100",
                new VideoCatalog.SegmentMetadata(1_000, 2_000, 1_000_000, 3, SHA_ONE, AVC));
        first.completeTrip("100", 2_000);

        VideoCatalog recovered = new VideoCatalog(temporaryFolder.getRoot());
        recovered.recover();

        List<VideoCatalog.Trip> snapshot = recovered.completedSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals(100L, snapshot.get(0).tripId);
        assertEquals("000001.mp4", snapshot.get(0).segments.get(0).fileName);
    }

    @Test
    public void discardRemovesOnlyValidatedTripDirectory() throws Exception {
        VideoCatalog catalog = new VideoCatalog(temporaryFolder.getRoot());
        catalog.beginTrip("123", 1_000, VideoCatalog.Source.AUTOMATIC);
        catalog.discardTrip("123");

        assertFalse(tripDirectory("123").exists());
        assertTrue(catalog.localSnapshot().isEmpty());
    }

    @Test
    public void nonNumericAndPathTripIdsAreRejected() throws Exception {
        VideoCatalog catalog = new VideoCatalog(temporaryFolder.getRoot());
        assertRejected(catalog, "manual");
        assertRejected(catalog, "../12");
        assertRejected(catalog, "12/34");
        assertRejected(catalog, "-1");
    }

    private void assertRejected(VideoCatalog catalog, String tripId) throws Exception {
        try {
            catalog.beginTrip(tripId, 1_000, VideoCatalog.Source.AUTOMATIC);
            fail("Expected invalid trip ID: " + tripId);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("trip ID"));
        }
    }

    private void writePartial(String tripId, String contents) throws Exception {
        File directory = tripDirectory(tripId);
        assertTrue(directory.isDirectory());
        Files.write(new File(directory, "current.partial").toPath(),
                contents.getBytes(StandardCharsets.UTF_8));
    }

    private File tripDirectory(String tripId) {
        return new File(temporaryFolder.getRoot(), "trip-video/" + tripId);
    }
}
