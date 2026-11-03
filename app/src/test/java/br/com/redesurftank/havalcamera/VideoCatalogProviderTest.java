package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class VideoCatalogProviderTest {
    private static final String SHA_ONE =
            "7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed";
    private static final String SHA_TWO =
            "3fc4ccfe745870e2c0d99f71f30ff0656c8dedd41cc1d7d3d376b0dbe685e2f3";
    private static final VideoCatalog.Codec AVC =
            new VideoCatalog.Codec("video/avc", 1760, 720, 15, 4_000_000);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void providerPublishesCanonicalAdditiveSegmentMetadataAndKeepsCount()
            throws Exception {
        VideoCatalog catalog = new VideoCatalog(temporaryFolder.getRoot());
        catalog.beginTrip("42", 1_000, VideoCatalog.Source.AUTOMATIC);
        writePartial("42", "one");
        catalog.completeSegment("42",
                new VideoCatalog.SegmentMetadata(1_000, 2_000, 1_000_000, 3, SHA_ONE, AVC));
        writePartial("42", "two");
        catalog.completeSegment("42",
                new VideoCatalog.SegmentMetadata(2_000, 3_000, 1_000_000, 3, SHA_TWO, AVC));
        catalog.completeTrip("42", 3_000);

        VideoCatalog.Trip trip = catalog.completedSnapshot().get(0);
        String metadata = VideoCatalogProvider.segmentMetadataJson(trip);

        assertEquals(2, trip.segments.size());
        assertEquals("[{\"segmentId\":1,\"byteSize\":3,\"sha256\":\"" + SHA_ONE
                + "\"},{\"segmentId\":2,\"byteSize\":3,\"sha256\":\"" + SHA_TWO + "\"}]", metadata);
        assertFalse(metadata.contains("fileName"));
        assertFalse(metadata.contains("codec"));
        assertFalse(metadata.contains("path"));
        assertTrue(metadata.length() < 1_024);
    }

    private void writePartial(String tripId, String contents) throws Exception {
        File directory = new File(temporaryFolder.getRoot(), "trip-video/" + tripId);
        Files.write(new File(directory, "current.partial").toPath(),
                contents.getBytes(StandardCharsets.UTF_8));
    }
}
