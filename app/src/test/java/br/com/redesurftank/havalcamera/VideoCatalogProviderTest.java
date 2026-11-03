package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
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

    @Test public void providerBoundsMetadataBeforeBinderAllocation() throws Exception {
        assertTrue(VideoCatalogProvider.segmentMetadataJson(tripWithSegments(256)).length() < 1_048_576);
        assertTrue(VideoCatalogProvider.segmentMetadataJson(tripWithSegments(4_096)).length() < 1_048_576);
        try {
            VideoCatalogProvider.segmentMetadataJson(tripWithSegments(4_097));
            throw new AssertionError("Expected provider metadata bound");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("metadata"));
        }
    }

    private static VideoCatalog.Trip tripWithSegments(int count) throws Exception {
        Constructor<VideoCatalog.Segment> segmentConstructor = VideoCatalog.Segment.class
                .getDeclaredConstructor(int.class, String.class, VideoCatalog.SegmentMetadata.class);
        segmentConstructor.setAccessible(true);
        List<VideoCatalog.Segment> segments = new ArrayList<>();
        VideoCatalog.SegmentMetadata metadata =
                new VideoCatalog.SegmentMetadata(1, 2, 1, 1, SHA_ONE, AVC);
        for (int index = 1; index <= count; index++) {
            segments.add(segmentConstructor.newInstance(index,
                    String.format("%06d.mp4", index), metadata));
        }
        Constructor<VideoCatalog.Trip> tripConstructor = VideoCatalog.Trip.class.getDeclaredConstructor(
                long.class, VideoCatalog.Source.class, long.class, Long.class, boolean.class, List.class);
        tripConstructor.setAccessible(true);
        return tripConstructor.newInstance(42L, VideoCatalog.Source.AUTOMATIC, 1L, 2L, true, segments);
    }

    private void writePartial(String tripId, String contents) throws Exception {
        File directory = new File(temporaryFolder.getRoot(), "trip-video/" + tripId);
        Files.write(new File(directory, "current.partial").toPath(),
                contents.getBytes(StandardCharsets.UTF_8));
    }
}
