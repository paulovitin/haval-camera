package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RecordingCoordinatorTest {
    @Test
    public void recorderReceivesEveryAccessUnitInOrder() {
        List<Long> recorded = new ArrayList<>();
        RecordingCoordinator coordinator = coordinator(
                unit -> recorded.add(unit.presentationTimeUs), () -> { }, () -> { }, failure -> { });

        coordinator.onEncodedAccessUnit(unit(1));
        coordinator.onEncodedAccessUnit(unit(2));
        coordinator.onEncodedAccessUnit(unit(3));

        assertEquals(List.of(1L, 2L, 3L), recorded);
        coordinator.close();
    }

    @Test(timeout = 2_000)
    public void blockedLiveSinkCannotBlockRecorder() throws Exception {
        List<Long> recorded = new ArrayList<>();
        CountDownLatch liveEntered = new CountDownLatch(1);
        CountDownLatch releaseLive = new CountDownLatch(1);
        RecordingCoordinator coordinator = coordinator(
                unit -> recorded.add(unit.presentationTimeUs), () -> { }, () -> { }, failure -> { });
        coordinator.attachLiveSink(unit -> {
            liveEntered.countDown();
            releaseLive.await();
        });

        coordinator.onEncodedAccessUnit(unit(1));
        assertTrue(liveEntered.await(1, TimeUnit.SECONDS));
        coordinator.onEncodedAccessUnit(unit(2));

        assertEquals(List.of(1L, 2L), recorded);
        releaseLive.countDown();
        coordinator.close();
    }

    @Test
    public void attachingLiveSinkRequestsKeyframe() {
        AtomicInteger requests = new AtomicInteger();
        RecordingCoordinator coordinator = coordinator(
                unit -> { }, () -> { }, requests::incrementAndGet, failure -> { });

        coordinator.attachLiveSink(unit -> { });

        assertEquals(1, requests.get());
        coordinator.close();
    }

    @Test(timeout = 2_000)
    public void failingLiveSinkDetachesWithoutReportingRecorderFailure() throws Exception {
        List<Long> recorded = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger liveCalls = new AtomicInteger();
        CountDownLatch liveCalled = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        RecordingCoordinator coordinator = coordinator(
                unit -> recorded.add(unit.presentationTimeUs), () -> { }, () -> { },
                failure -> failureReported.countDown());
        coordinator.attachLiveSink(unit -> {
            liveCalls.incrementAndGet();
            liveCalled.countDown();
            throw new IllegalStateException("network failed");
        });

        coordinator.onEncodedAccessUnit(unit(1));
        assertTrue(liveCalled.await(1, TimeUnit.SECONDS));
        coordinator.onEncodedAccessUnit(unit(2));

        assertEquals(List.of(1L, 2L), recorded);
        assertEquals(1, liveCalls.get());
        assertEquals(1, failureReported.getCount());
        coordinator.close();
    }

    @Test
    public void codecFailureStopsCaptureWithoutFallback() {
        AtomicInteger captureStops = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        RecordingCoordinator coordinator = coordinator(
                unit -> { }, captureStops::incrementAndGet, () -> { },
                failure -> failures.incrementAndGet());

        coordinator.onCodecFailure(new IllegalStateException("codec failed"));

        assertEquals(1, captureStops.get());
        assertEquals(1, failures.get());
        assertFalse(coordinator.isRunning());
        coordinator.close();
    }

    private static RecordingCoordinator coordinator(
            RecordingCoordinator.Sink recorder,
            RecordingCoordinator.Capture capture,
            RecordingCoordinator.EncoderControl encoder,
            RecordingCoordinator.FailureListener failures) {
        return new RecordingCoordinator(recorder, capture, encoder, failures);
    }

    private static EncodedAccessUnit unit(long ptsUs) {
        return new EncodedAccessUnit(new byte[]{1, 2, 3}, ptsUs, false, false);
    }
}
