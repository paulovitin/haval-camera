package br.com.redesurftank.havalcamera;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Reflection keeps the tests-only RED compilable so the prior suite still runs. */
public final class RecordingTransferTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void producerFirstWaitsForExplicitReadinessEvenWhenInitialPausedIsFalse() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(false); f.open(); f.quiet(0);
            f.text("{\"type\":\"pause\",\"reason\":\"viewer\"}"); f.quiet(0);
            f.resume(0); f.awaitFrames(1);
            assertEquals(0, f.message(0).offset);
            assertEquals("Bearer " + "a".repeat(32), f.factory.requests.get(0).header("Authorization"));
            assertEquals("https://bridge.example/ws/video/transfer/car?session=transfer-1",
                    f.factory.requests.get(0).url().toString());
        }
    }

    @Test public void zeroStartChunkHashWholeHashAndEofRequireDurableAck() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            TransferChunkCodec.Message data = f.message(0);
            assertEquals(TransferChunkCodec.Type.DATA, data.type);
            assertArrayEquals(new byte[]{0, 1, 2}, data.payload);
            assertArrayEquals(hash(new byte[]{0, 1, 2}), data.sha256);
            f.quiet(1); assertEquals("0", f.offsets().get("1"));
            f.ack(1, 3); f.awaitFrames(2);
            TransferChunkCodec.Message eof = f.message(1);
            assertEquals(TransferChunkCodec.Type.EOF, eof.type);
            assertEquals(3, eof.offset); assertArrayEquals(hash(data.payload), eof.sha256);
            assertFalse(f.catalog.leasedTripIds().isEmpty());
            f.ack(1, 3); await(() -> f.ends.get() == 1);
            assertTrue(f.catalog.leasedTripIds().isEmpty());
            assertEquals(4000, f.socket().closeCode);
        }
    }

    @Test public void chunksUseOneReusable262144ByteReadBuffer() throws Exception {
        try (Fixture f = fixture(524291)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            Object buffer = null;
            for (java.lang.reflect.Field field : f.transfer.getClass().getDeclaredFields()) {
                if (field.getType() != byte[].class) continue;
                field.setAccessible(true); byte[] value = (byte[]) field.get(f.transfer);
                if (value != null && value.length == 262144) {
                    assertNull("Only one full transfer read buffer", buffer); buffer = value;
                }
            }
            assertNotNull(buffer);
            assertEquals(262144, f.message(0).payload.length);
            f.ack(1, 262144); f.awaitFrames(2);
            assertEquals(262144, f.message(1).payload.length);
            assertEquals(262144, f.message(1).offset);
            f.ack(1, 524288); f.awaitFrames(3);
            assertEquals(3, f.message(2).payload.length); assertEquals(524288, f.message(2).offset);
            f.ack(1, 524291); f.awaitFrames(4);
            assertArrayEquals(hash(f.bytes), f.message(3).sha256);
            for (java.lang.reflect.Field field : f.transfer.getClass().getDeclaredFields()) {
                if (field.getType() == byte[].class) {
                    field.setAccessible(true); byte[] value = (byte[]) field.get(f.transfer);
                    if (value != null && value.length == 262144) assertSame(buffer, value);
                }
            }
        }
    }

    @Test public void freshAuthorizationSeeksToExactAckAndHashesWholeFile() throws Exception {
        try (Fixture f = fixture(262149)) {
            f.startAt(262144, Collections.emptySet()); f.open(); f.resume(262144); f.awaitFrames(1);
            assertEquals(262144, f.message(0).offset);
            assertArrayEquals(Arrays.copyOfRange(f.bytes, 262144, f.bytes.length), f.message(0).payload);
            f.ack(1, 262149); f.awaitFrames(2);
            assertArrayEquals(hash(f.bytes), f.message(1).sha256);
        }
    }

    @Test public void resumeAtFileLengthStillRequiresEofAck() throws Exception {
        try (Fixture f = fixture(3)) {
            f.startAt(3, Collections.emptySet()); f.open(); f.resume(3); f.awaitFrames(1);
            assertEquals(TransferChunkCodec.Type.EOF, f.message(0).type);
            assertEquals(0, f.ends.get());
            f.ack(1, 3); await(() -> f.ends.get() == 1);
        }
    }

    @Test public void wrongAndUnsignedAckNeverAdvanceProgress() throws Exception {
        for (long offset : new long[]{0, 2, 4, Long.MIN_VALUE, -1L}) {
            try (Fixture f = fixture(3)) {
                f.start(true); f.open(); f.resume(0); f.awaitFrames(1); f.ack(1, offset);
                await(() -> f.ends.get() == 1);
                assertEquals("0", f.offsets().get("1")); assertEquals(4004, f.socket().closeCode);
                assertTrue(f.catalog.leasedTripIds().isEmpty());
            }
        }
    }

    @Test public void unknownUnsignedOrdinalAndUnsolicitedAckAreProtocolErrors() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1); f.ack(-1, 3);
            await(() -> f.ends.get() == 1); assertEquals(4004, f.socket().closeCode);
        }
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.ack(1, 0);
            await(() -> f.ends.get() == 1); assertEquals(4004, f.socket().closeCode);
        }
    }

    @Test public void pauseDropsNoAckAndOnlyExplicitResumeRereadsChunk() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            f.text("{\"type\":\"pause\",\"reason\":\"live\"}"); f.quiet(1);
            f.resume(0); f.awaitFrames(2);
            assertArrayEquals(f.socket().binary.get(0).toByteArray(), f.socket().binary.get(1).toByteArray());
            f.quiet(2);
        }
    }

    @Test public void pendingAckMayFinishDuringPauseWithoutSendingNextChunk() throws Exception {
        try (Fixture f = fixture(262145)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            f.text("{\"type\":\"pause\",\"reason\":\"live\"}"); f.ack(1, 262144); f.quiet(1);
            assertEquals("262144", f.offsets().get("1"));
            f.resume(262144); f.awaitFrames(2); assertEquals(262144, f.message(1).offset);
        }
    }

    @Test public void localLivePriorityNeverResumesWithoutBridgeControl() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.live(true); f.quiet(0);
            f.live(false); f.quiet(0); f.resume(0); f.awaitFrames(1);
        }
    }

    @Test public void explicitBridgeResumeBeforeLocalLiveCloseIsRememberedButCannotSendDuringLive() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.live(true); f.resume(0); f.quiet(0);
            f.live(false); f.awaitFrames(1);
        }
    }

    @Test public void consumedTokenCannotAuthorizeAnotherAttempt() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.factory.fail(0);
            assertThrows(IllegalArgumentException.class, () -> f.start(true));
            assertTrue(f.catalog.leasedTripIds().isEmpty()); assertEquals(1, f.factory.requests.size());
            f.token = "b".repeat(32); f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
        }
    }

    @Test public void completedCatalogMutationsNotifyButLeaseChangesDoNot() throws Exception {
        try (Fixture f = fixture(3)) {
            AtomicInteger changes = new AtomicInteger();
            invoke(VideoCatalog.class.getMethod("setOnChanged", Runnable.class), f.catalog,
                    (Runnable) changes::incrementAndGet);
            try (VideoCatalog.ReadLease lease = f.catalog.acquireReadLease("42")) {
                assertEquals(0, changes.get());
            }
            f.catalog.beginTrip("43", 1000, VideoCatalog.Source.MANUAL);
            int before = changes.get(); f.catalog.completeTrip("43", 2000);
            assertEquals(before + 1, changes.get());
            assertEquals(2, f.catalog.completedSnapshot().size());
            f.catalog.discardTrip("43"); assertEquals(before + 2, changes.get());
            assertEquals(1, f.catalog.completedSnapshot().size());
        }
    }

    @Test public void duplicateResumeDoesNotRetransmitOutstandingFrame() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1); f.resume(0); f.quiet(1);
        }
    }

    @Test public void resumeCannotSkipOrRewindConfirmedBytes() throws Exception {
        try (Fixture f = fixture(262145)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            f.text("{\"type\":\"pause\",\"reason\":\"live\"}"); f.ack(1, 262144);
            f.resume(0); await(() -> f.ends.get() == 1); assertEquals(4004, f.socket().closeCode);
            assertEquals("262144", f.offsets().get("1"));
        }
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.resume(1);
            await(() -> f.ends.get() == 1); assertEquals(4004, f.socket().closeCode);
        }
    }

    @Test public void activeAndPartialOnlyTripsCannotBeTransferred() throws Exception {
        try (Fixture f = fixture(3)) {
            f.catalog.beginTrip("43", 1000, VideoCatalog.Source.AUTOMATIC);
            Files.write(new File(f.root, "trip-video/43/current.partial").toPath(), f.bytes);
            assertThrows(Exception.class, () -> f.start("43", List.of("1"), Map.of("1", "0"), Set.of(), true));
            assertEquals(0, f.factory.requests.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void missingFileAndSizeMismatchReleaseLeaseBeforeConnecting() throws Exception {
        for (boolean missing : new boolean[]{true, false}) {
            try (Fixture f = fixture(3)) {
                if (missing) Files.delete(f.file().toPath()); else Files.write(f.file().toPath(), new byte[2]);
                assertThrows(Exception.class, () -> f.start(true));
                assertEquals(0, f.factory.requests.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
            }
        }
    }

    @Test public void tripAndSegmentTraversalAndUnknownIdsAreRefused() throws Exception {
        for (String id : new String[]{"../42", "current.partial", "1/../../42", "2", "4294967295"}) {
            try (Fixture f = fixture(3)) {
                assertThrows(Exception.class, () -> f.start("42", List.of(id), Map.of(id, "0"), Set.of(), true));
                assertTrue(f.catalog.leasedTripIds().isEmpty()); assertEquals(0, f.factory.requests.size());
            }
        }
        try (Fixture f = fixture(3)) {
            assertThrows(Exception.class, () -> f.start("../42", List.of("1"), Map.of("1", "0"), Set.of(), true));
            assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void symlinkedSegmentCannotEscapeOrAliasCatalogContent() throws Exception {
        try (Fixture f = fixture(3)) {
            File other = new File(f.root, "other.mp4"); Files.write(other.toPath(), f.bytes);
            Files.delete(f.file().toPath()); Files.createSymbolicLink(f.file().toPath(), other.toPath());
            assertThrows(Exception.class, () -> f.start(true));
            assertEquals(0, f.factory.requests.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void closedOrForeignLeaseCannotOpenAnySegment() throws Exception {
        try (Fixture f = fixture(3)) {
            Method open = VideoCatalog.class.getMethod("openTransferSegment", VideoCatalog.ReadLease.class, String.class);
            VideoCatalog.ReadLease lease = f.catalog.acquireReadLease("42");
            try (AutoCloseable file = (AutoCloseable) invoke(open, f.catalog, lease, "1")) {
                long length = file instanceof RandomAccessFile ? ((RandomAccessFile) file).length()
                        : ((java.nio.channels.FileChannel) file).size();
                assertEquals(3, length);
            }
            lease.close();
            assertThrows(Exception.class, () -> invoke(open, f.catalog, lease, "1"));
            VideoCatalog other = new VideoCatalog(temporary.newFolder());
            try (VideoCatalog.ReadLease fresh = f.catalog.acquireReadLease("42")) {
                assertThrows(Exception.class, () -> invoke(open, other, fresh, "1"));
            }
        }
    }

    @Test public void cancelWhileIdleProtectsThenReleasesRetentionLease() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.quiet(0);
            assertThrows(IllegalStateException.class, () -> f.catalog.discardTrip("42"));
            assertThrows(IllegalStateException.class, () -> f.catalog.beginTrip("42", 1000, VideoCatalog.Source.MANUAL));
            f.cancel(); await(() -> f.ends.get() == 1);
            f.catalog.discardTrip("42"); assertTrue(f.catalog.completedSnapshot().isEmpty());
            assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void lossNeverRetriesAndNewTokenResumesOnlyFromAuthorizedAck() throws Exception {
        try (Fixture f = fixture(262145)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1); f.ack(1, 262144); f.awaitFrames(2);
            f.factory.fail(0); await(() -> f.ends.get() == 1); f.quiet(2);
            assertEquals(1, f.factory.requests.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
            f.token = "b".repeat(32); f.startAt(262144, Set.of()); f.open(); f.quiet(0);
            f.resume(262144); f.awaitFrames(1);
            assertEquals(262144, f.message(0).offset);
            // Late callbacks from the first attempt cannot end the new one.
            f.factory.fail(0); assertEquals(1, f.ends.get()); assertFalse(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void sentButUnacknowledgedBytesNeverBecomeResumeState() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1); f.factory.fail(0);
            assertEquals("0", f.offsets().get("1"));
            f.token = "b".repeat(32); f.startAt(0, Set.of()); f.open(); f.resume(0); f.awaitFrames(1);
            assertEquals(0, f.message(0).offset);
        }
    }

    @Test public void allSelectedFilesAreValidatedBeforeAnySocketOrBytes() throws Exception {
        try (Fixture f = fixture(3)) {
            assertThrows(Exception.class, () -> f.start("42", List.of("1", "2"), Map.of("1", "0", "2", "0"), Set.of(), true));
            assertEquals(0, f.factory.requests.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void multipleSegmentsWaitForEachEofAckAndSkipAuthorizedCompletedSegments() throws Exception {
        try (Fixture f = fixture(3, 4)) {
            f.start("42", List.of("1", "2"), Map.of("1", "3", "2", "0"), Set.of("1"), true);
            f.open(); f.text("{\"type\":\"resume\",\"offsets\":{\"1\":\"3\",\"2\":\"0\"}}"); f.awaitFrames(1);
            assertEquals(2, f.message(0).ordinal); f.ack(2, 4); f.awaitFrames(2); f.ack(2, 4);
            await(() -> f.ends.get() == 1); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
        try (Fixture f = fixture(3, 4)) {
            f.start("42", List.of("1", "2"), Map.of("1", "0", "2", "0"), Set.of(), true);
            f.open(); f.text("{\"type\":\"resume\",\"offsets\":{\"1\":\"0\",\"2\":\"0\"}}");
            f.awaitFrames(1); f.ack(1, 3); f.awaitFrames(2); f.quiet(2);
            f.ack(1, 3); f.awaitFrames(3); assertEquals(2, f.message(2).ordinal);
        }
    }

    @Test public void invalidInitialOffsetsAndCompletedMembershipAreRefusedWithoutLeaseLeak() throws Exception {
        for (String offset : new String[]{"4", "-1", "9223372036854775808", "18446744073709551615", "18446744073709551616"}) {
            try (Fixture f = fixture(3)) {
                assertThrows(Exception.class, () -> f.start("42", List.of("1"), Map.of("1", offset), Set.of(), true));
                assertEquals(0, f.factory.requests.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
            }
        }
        try (Fixture f = fixture(3)) {
            assertThrows(Exception.class, () -> f.start("42", List.of("1"), Map.of("1", "0"), Set.of("1"), true));
            assertThrows(Exception.class, () -> f.start("42", List.of("1"), Map.of("1", "0"), Set.of("2"), true));
            assertThrows(Exception.class, () -> f.start("42", List.of("1", "1"), Map.of("1", "0"), Set.of(), true));
            assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void corruptWholeFileCannotBeSentEvenWithMatchingSize() throws Exception {
        try (Fixture f = fixture(3)) {
            Files.write(f.file().toPath(), new byte[]{7, 8, 9});
            f.start(true); f.open(); f.resume(0); await(() -> f.ends.get() == 1);
            assertTrue(f.catalog.leasedTripIds().isEmpty());
            for (ByteString frame : f.socket().binary) assertEquals(TransferChunkCodec.Type.ERROR,
                    TransferChunkCodec.decode(frame.asByteBuffer()).type);
        }
    }

    @Test public void invalidControlAndBinaryTerminateWith4004AndReleaseLease() throws Exception {
        for (String control : new String[]{"{}", "{\"type\":\"resume\",\"offsets\":{}}",
                "{\"type\":\"resume\",\"offsets\":{\"1\":\"0\",\"1\":\"0\"}}",
                "{\"type\":\"resume\",\"offsets\":{\"1\":\"18446744073709551615\"}}"}) {
            try (Fixture f = fixture(3)) {
                f.start(true); f.open(); f.text(control); await(() -> f.ends.get() == 1);
                assertEquals(4004, f.socket().closeCode); assertTrue(f.catalog.leasedTripIds().isEmpty());
            }
        }
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.binary(ByteBuffer.wrap(new byte[56]));
            assertEquals(4004, f.socket().closeCode); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void remoteErrorAndClosingReleaseLeaseWithoutAdvancingAck() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.binary(TransferChunkCodec.encodeError(1, "unavailable"));
            await(() -> f.ends.get() == 1); assertEquals(4000, f.socket().closeCode);
            assertTrue(f.catalog.leasedTripIds().isEmpty()); assertEquals("0", f.offsets().get("1"));
        }
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.factory.listeners.get(0).onClosing(f.socket(), 4000, "done");
            await(() -> f.ends.get() == 1); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void backpressureDoesNotReadAheadOrAdvanceAckAndSendFailureReleasesLease() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.socket().queued = 1; f.resume(0); f.quiet(0);
            f.socket().queued = 0; f.awaitFrames(1);
            assertEquals("0", f.offsets().get("1"));
        }
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.socket().accept = false; f.resume(0);
            await(() -> f.ends.get() == 1); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
        try (Fixture f = fixture(3)) {
            f.factory.throwOnConnect = true;
            assertThrows(Exception.class, () -> f.start(true)); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void expiryAndReplacementReleaseEveryLease() throws Exception {
        try (Fixture f = fixture(3)) {
            f.expires = f.now.get() + 80; f.start(true); f.open();
            await(() -> f.ends.get() == 1); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
        try (Fixture f = fixture(3)) {
            f.start(true); f.open(); f.token = "b".repeat(32); f.start(true);
            f.cancel(); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void transferEffectsNeverStartOrFailRecordingEvenWhenIdleOrAlreadyRecording() throws Exception {
        for (boolean recording : new boolean[]{false, true}) {
            ServiceStateReducer reducer = new ServiceStateReducer();
            if (recording) reducer.apply(ServiceStateReducer.Event.startAutomatic("99", 1));
            ServiceStateReducer.Mode before = reducer.state().mode;
            try (Fixture f = fixture(3)) {
                List<ServiceStateReducer.Effect> effects = new ArrayList<>(reducer.apply(ServiceStateReducer.Event.transferStarted(2)));
                f.start(true); f.open(); f.factory.fail(0);
                effects.addAll(reducer.apply(ServiceStateReducer.Event.transferStopped(3)));
                assertEquals(before, reducer.state().mode);
                assertNull(reducer.state().error);
                for (ServiceStateReducer.Effect effect : effects)
                    assertEquals(ServiceStateReducer.EffectType.CANCEL_TRANSFER, effect.type);
                assertTrue(f.file().isFile()); assertTrue(f.catalog.leasedTripIds().isEmpty());
            }
        }
    }

    @Test public void retentionReplansStaleSelectionAfterTransferLeaseAndDeletesOtherCandidate() throws Exception {
        try (Fixture f = fixture(3)) {
            addCompletedTrip(f, "43", 3000, new byte[]{3, 4, 5});
            List<RetentionPlanner.Entry> entries = List.of(
                    new RetentionPlanner.Entry(42, 1000, List.of(3L), true),
                    new RetentionPlanner.Entry(43, 3000, List.of(3L), true));
            RetentionPlanner.Plan stale = RetentionPlanner.selectDeletions(entries, 0, 3, true,
                    null, f.catalog.leasedTripIds());
            assertEquals(List.of(42L), stale.tripIds);
            // Precisely the reviewed interleaving: transfer leases the old selection before deletion.
            f.start(true); f.open();
            File alternative = new File(f.root, "trip-video/43");
            enforceReserve(f.catalog, () -> alternative.exists() ? 0L : 3L, 3, true);
            assertTrue(f.file().isFile()); assertFalse(alternative.exists());
            assertTrue(f.catalog.leasedTripIds().contains(42L)); assertEquals(0, f.ends.get());
            f.resume(0); f.awaitFrames(1); assertArrayEquals(new byte[]{0, 1, 2}, f.message(0).payload);
        }
    }

    @Test public void retentionCannotRecoverReserveFromLeasedOrActiveTrips() throws Exception {
        try (Fixture f = fixture(3)) {
            f.start(true); f.open();
            f.catalog.beginTrip("43", 3000, VideoCatalog.Source.MANUAL);
            Files.write(new File(f.root, "trip-video/43/current.partial").toPath(), new byte[]{3, 4, 5});
            assertThrows(IOException.class, () -> enforceReserve(f.catalog, () -> 0L, 3, true));
            assertTrue(f.file().isFile()); assertTrue(new File(f.root, "trip-video/43/current.partial").isFile());
            assertEquals(0, f.ends.get());
        }
    }

    @Test public void retentionPreservesDisabledDeletionAndActualFreeSpaceGate() throws Exception {
        try (Fixture f = fixture(3)) {
            addCompletedTrip(f, "43", 3000, new byte[]{3, 4, 5}); f.start(true); f.open();
            assertThrows(IOException.class, () -> enforceReserve(f.catalog, () -> 0L, 3, false));
            assertTrue(new File(f.root, "trip-video/43/000001.mp4").isFile());
            // Deletion estimates cannot authorize recording if the real free-space query stays below reserve.
            assertThrows(IOException.class, () -> enforceReserve(f.catalog, () -> 0L, 3, true));
            assertTrue(f.file().isFile()); assertEquals(0, f.ends.get());
        }
    }

    @Test public void replacingSegmentAfterFirstChunkNeverSendsReplacementBytes() throws Exception {
        try (Fixture f = fixture(262147)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            byte[] replacement = new byte[f.bytes.length]; Arrays.fill(replacement, (byte) 99);
            File swapped = new File(f.root, "replacement.mp4"); Files.write(swapped.toPath(), replacement);
            Files.move(swapped.toPath(), f.file().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            f.ack(1, 262144);
            await(() -> f.ends.get() == 1 || f.socket().binary.size() > 1);
            if (f.socket().binary.size() > 1) {
                assertEquals(TransferChunkCodec.Type.DATA, f.message(1).type);
                assertArrayEquals("Only the original verified identity may continue",
                        Arrays.copyOfRange(f.bytes, 262144, 262147), f.message(1).payload);
                f.ack(1, 262147); f.awaitFrames(3);
                assertArrayEquals(hash(f.bytes), f.message(2).sha256);
                f.ack(1, 262147);
            }
            await(() -> f.ends.get() == 1); assertTrue(f.catalog.leasedTripIds().isEmpty());
        }
    }

    @Test public void pausedRetransmitCannotReadReplacementIdentityAtConfirmedOffset() throws Exception {
        try (Fixture f = fixture(262147)) {
            f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
            f.text("{\"type\":\"pause\",\"reason\":\"live\"}");
            byte[] replacement = new byte[f.bytes.length]; Arrays.fill(replacement, (byte) 99);
            File swapped = new File(f.root, "replacement.mp4"); Files.write(swapped.toPath(), replacement);
            Files.move(swapped.toPath(), f.file().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            f.resume(0); await(() -> f.ends.get() == 1 || f.socket().binary.size() > 1);
            if (f.socket().binary.size() > 1) assertArrayEquals(
                    Arrays.copyOfRange(f.bytes, 0, 262144), f.message(1).payload);
            assertEquals("0", f.offsets().get("1"));
        }
    }

    @Test public void symlinkAndSizeReplacementAfterFirstChunkStillFailClosed() throws Exception {
        for (boolean symlink : new boolean[]{true, false}) {
            try (Fixture f = fixture(262147)) {
                f.start(true); f.open(); f.resume(0); f.awaitFrames(1);
                Files.delete(f.file().toPath());
                if (symlink) {
                    File target = new File(f.root, "target.mp4"); Files.write(target.toPath(), f.bytes);
                    Files.createSymbolicLink(f.file().toPath(), target.toPath());
                } else Files.write(f.file().toPath(), new byte[2]);
                f.ack(1, 262144); await(() -> f.ends.get() == 1);
                assertEquals(1, f.socket().binary.size()); assertTrue(f.catalog.leasedTripIds().isEmpty());
                assertEquals("262144", f.offsets().get("1"));
            }
        }
    }

    private static void addCompletedTrip(Fixture f, String id, long start, byte[] bytes) throws Exception {
        f.catalog.beginTrip(id, start, VideoCatalog.Source.MANUAL);
        Files.write(new File(f.root, "trip-video/" + id + "/current.partial").toPath(), bytes);
        f.catalog.completeSegment(id, new VideoCatalog.SegmentMetadata(start, start + 1000, 1000000,
                bytes.length, hex(hash(bytes)), new VideoCatalog.Codec("video/avc", 1760, 720, 15, 4000000)));
        f.catalog.completeTrip(id, start + 1000);
    }

    private static void enforceReserve(VideoCatalog catalog, LongSupplier free, long reserve, boolean deletion) throws Exception {
        Method method;
        try { method = VideoCatalog.class.getMethod("enforceStorageReserve", LongSupplier.class,
                long.class, boolean.class, Long.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Atomic catalog reserve gate is missing", missing); }
        invoke(method, catalog, free, reserve, deletion, null);
    }

    private Fixture fixture(int... sizes) throws Exception { return new Fixture(temporary.newFolder(), sizes); }

    static final class Fixture implements AutoCloseable {
        final File root;
        final VideoCatalog catalog;
        final FakeFactory factory = new FakeFactory();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicLong now = new AtomicLong(1_700_000_000_000L);
        final Object transfer;
        final byte[] bytes;
        String token = "a".repeat(32);
        long expires = now.get() + 60000;
        int attempt;
        Fixture(File root, int... sizes) throws Exception {
            this.root = root; catalog = new VideoCatalog(root);
            catalog.beginTrip("42", 1000, VideoCatalog.Source.MANUAL);
            byte[] first = null;
            for (int size : sizes) {
                byte[] data = new byte[size]; for (int i = 0; i < size; i++) data[i] = (byte) i;
                if (first == null) first = data;
                Files.write(new File(root, "trip-video/42/current.partial").toPath(), data);
                catalog.completeSegment("42", new VideoCatalog.SegmentMetadata(1000, 2000, 1000000,
                        size, hex(hash(data)), new VideoCatalog.Codec("video/avc", 1760, 720, 15, 4000000)));
            }
            bytes = first; catalog.completeTrip("42", 2000);
            try {
                transfer = Class.forName("br.com.redesurftank.havalcamera.RecordingTransfer")
                        .getConstructor(VideoCatalog.class, WebSocket.Factory.class, Runnable.class, LongSupplier.class)
                        .newInstance(catalog, factory, (Runnable) ends::incrementAndGet, (LongSupplier) now::get);
            } catch (ClassNotFoundException missing) { throw new AssertionError("RecordingTransfer producer is missing", missing); }
        }
        File file() { return new File(root, "trip-video/42/000001.mp4"); }
        void start(boolean paused) throws Exception { start("42", List.of("1"), Map.of("1", "0"), Set.of(), paused); }
        void startAt(long offset, Set<String> completed) throws Exception {
            start("42", List.of("1"), Map.of("1", Long.toUnsignedString(offset)), completed, true);
        }
        void start(String trip, List<String> ids, Map<String, String> offsets, Set<String> completed, boolean paused) throws Exception {
            attempt = factory.sockets.size();
            call("start", new Class<?>[]{VideoRelayClient.Session.class, String.class, List.class, Map.class, Set.class, boolean.class},
                    new VideoRelayClient.Session("wss://bridge.example/ws/video/transfer/car?session=transfer-1", token, expires),
                    trip, ids, offsets, completed, paused);
        }
        void open() { factory.listeners.get(attempt).onOpen(socket(), (Response) null); }
        FakeSocket socket() { return factory.sockets.get(attempt); }
        void text(String text) { factory.listeners.get(attempt).onMessage(socket(), text); }
        void binary(ByteBuffer bytes) { factory.listeners.get(attempt).onMessage(socket(), ByteString.of(bytes)); }
        void ack(int ordinal, long offset) { binary(TransferChunkCodec.encodeAck(ordinal, offset)); }
        void resume(long offset) { text("{\"type\":\"resume\",\"offsets\":{\"1\":\"" + Long.toUnsignedString(offset) + "\"}}"); }
        void live(boolean active) throws Exception { call("setLiveActive", new Class<?>[]{boolean.class}, active); }
        void cancel() throws Exception { call("cancel", new Class<?>[0]); }
        @SuppressWarnings("unchecked") Map<String, String> offsets() throws Exception {
            return (Map<String, String>) call("acknowledgedOffsets", new Class<?>[0]);
        }
        Object call(String name, Class<?>[] types, Object... args) throws Exception {
            return invoke(transfer.getClass().getMethod(name, types), transfer, args);
        }
        TransferChunkCodec.Message message(int i) { return TransferChunkCodec.decode(socket().binary.get(i).asByteBuffer()); }
        void awaitFrames(int count) throws Exception { await(() -> socket().binary.size() == count); }
        void quiet(int count) throws Exception { Thread.sleep(60); assertEquals(count, socket().binary.size()); }
        @Override public void close() throws Exception { call("close", new Class<?>[0]); }
    }

    static final class FakeFactory implements WebSocket.Factory {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final List<WebSocketListener> listeners = new CopyOnWriteArrayList<>();
        final List<FakeSocket> sockets = new CopyOnWriteArrayList<>();
        boolean throwOnConnect;
        @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
            if (throwOnConnect) throw new IllegalStateException("synthetic connect failure");
            requests.add(request); listeners.add(listener); FakeSocket socket = new FakeSocket(request); sockets.add(socket); return socket;
        }
        void fail(int i) { listeners.get(i).onFailure(sockets.get(i), new IOException("synthetic loss"), null); }
    }
    static final class FakeSocket implements WebSocket {
        final Request request;
        final List<ByteString> binary = new CopyOnWriteArrayList<>();
        volatile int closeCode;
        volatile long queued;
        volatile boolean accept = true;
        FakeSocket(Request request) { this.request = request; }
        @Override public Request request() { return request; }
        @Override public long queueSize() { return queued; }
        @Override public boolean send(String value) { throw new AssertionError("Producer must send HVT1 binary only"); }
        @Override public boolean send(ByteString bytes) { if (!accept) return false; binary.add(bytes); return true; }
        @Override public boolean close(int code, String reason) { closeCode = code; return true; }
        @Override public void cancel() { if (closeCode == 0) closeCode = 4000; }
    }
    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    interface Check { boolean done() throws Exception; }
    static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!check.done() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Timed out waiting for producer", check.done());
    }
    static byte[] hash(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
    static String hex(byte[] data) {
        StringBuilder value = new StringBuilder(); for (byte b : data) value.append(String.format("%02x", b & 255)); return value.toString();
    }
}
