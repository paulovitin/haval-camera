package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ServiceStateReducerTest {
    @Test public void modesAndViewerGracePreserveRecording() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);

        reducer.apply(ServiceStateReducer.Event.startAutomatic("10", 1));
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.viewerConnected(2, 1_000));
        assertEquals(ServiceStateReducer.Mode.RECORDING_LIVE, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.viewerLost(3, 2_000));
        assertEquals(ServiceStateReducer.Mode.RECORDING_LIVE, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.tick(4, 16_999));
        assertEquals(ServiceStateReducer.Mode.RECORDING_LIVE, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.tick(5, 17_000));
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
    }

    @Test public void manualAndParkedLiveWorkWithoutAutomaticTrip() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startManual("1700000000000", 1));
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        assertTrue(reducer.state().manualRecording);
        reducer.apply(ServiceStateReducer.Event.stopRecording(2));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(3, 5_000));
        assertEquals(ServiceStateReducer.Mode.LIVE_ONLY, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.tick(4, 1_805_000));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);
    }

    @Test public void livePausesTransferAndViewerLossResumesIt() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.transferStarted(1));
        assertTrue(reducer.state().transferActive);
        reducer.apply(ServiceStateReducer.Event.viewerConnected(2, 1_000));
        assertTrue(reducer.state().transferPaused);
        reducer.apply(ServiceStateReducer.Event.viewerLost(3, 2_000));
        reducer.apply(ServiceStateReducer.Event.tick(4, 17_000));
        assertTrue(reducer.state().transferActive);
        assertFalse(reducer.state().transferPaused);
    }

    @Test public void togglesApplyImmediatelyAndEnableCurrentTrip() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startAutomatic("55", 1));
        java.util.List<ServiceStateReducer.Effect> paused = reducer.apply(ServiceStateReducer.Event.settings(
                2, false, false, true, true, "55"));
        assertTrue(paused.stream().anyMatch(effect -> effect.type == ServiceStateReducer.EffectType.PAUSE_RECORDING));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.settings(
                3, true, false, true, true, "55"));
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        reducer.apply(ServiceStateReducer.Event.settings(
                4, true, false, false, false, "55"));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(5, 1_000));
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        assertFalse(reducer.state().transferActive);
    }

    @Test public void restartReconcilesPersistedTrip() {
        ServiceStateReducer reducer = new ServiceStateReducer(
                ServiceStateReducer.State.recovered("77", false));
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        assertEquals("77", reducer.state().tripId);
        assertTrue(reducer.apply(ServiceStateReducer.Event.stopRecording(9)).stream()
                .anyMatch(effect -> effect.type == ServiceStateReducer.EffectType.STOP_RECORDING));
    }

    @Test public void parkedLiveRecordingToggleRecordsOnlyAuthenticatedSession() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.settings(1, true, true, true, true, null));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(2, 1_700_000_000_000L));
        assertEquals(ServiceStateReducer.Mode.RECORDING_LIVE, reducer.state().mode);
        assertTrue(reducer.state().manualRecording);
        assertEquals("1700000000000", reducer.state().tripId);
    }

    @Test public void stoppingRecordingKeepsLiveOnlyCaptureRunning() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startAutomatic("10", 1));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(2, 1_000));
        java.util.List<ServiceStateReducer.Effect> effects =
                reducer.apply(ServiceStateReducer.Event.stopRecording(3));
        assertEquals(ServiceStateReducer.Mode.LIVE_ONLY, reducer.state().mode);
        assertTrue(effects.stream().anyMatch(effect -> effect.type == ServiceStateReducer.EffectType.START_CAPTURE));
    }

    @Test public void manualCommandsCannotAffectAutomaticRecording() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startAutomatic("10", 1));
        assertTrue(reducer.apply(ServiceStateReducer.Event.startManual("20", 2)).isEmpty());
        assertTrue(reducer.apply(ServiceStateReducer.Event.stopManual(3)).isEmpty());
        assertEquals("10", reducer.state().tripId);
    }

    @Test public void parkedRecordingStopsAfterGraceExpiryAndDisable() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.settings(1, true, true, true, true, null));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(2, 100_000));
        assertTrue(reducer.state().parkedRecording);
        reducer.apply(ServiceStateReducer.Event.viewerLost(3, 101_000));
        reducer.apply(ServiceStateReducer.Event.tick(4, 116_000));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);

        reducer.apply(ServiceStateReducer.Event.settings(5, true, true, true, true, null));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(6, 200_000));
        reducer.apply(ServiceStateReducer.Event.settings(7, true, false, true, true, null));
        assertEquals(ServiceStateReducer.Mode.LIVE_ONLY, reducer.state().mode);
        assertFalse(reducer.state().parkedRecording);
        assertEquals(null, reducer.state().tripId);
    }

    @Test public void authoritativeStopStillFinalizesWhileAutomaticRecordingPaused() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startAutomatic("10", 1));
        reducer.apply(ServiceStateReducer.Event.settings(2, false, false, true, true, "10"));
        assertEquals("10", reducer.state().authoritativeTripId);
        assertTrue(reducer.apply(ServiceStateReducer.Event.stopAutomatic("10", 3)).stream()
                .anyMatch(effect -> effect.type == ServiceStateReducer.EffectType.FINALIZE_TRIP));
    }

    @Test public void pipelineFailureStopsCaptureAndErrorSurvivesTicks() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startAutomatic("10", 1));
        assertTrue(reducer.apply(ServiceStateReducer.Event.pipelineFailed(2, "codec")).stream()
                .anyMatch(effect -> effect.type == ServiceStateReducer.EffectType.STOP_CAPTURE));
        reducer.apply(ServiceStateReducer.Event.tick(3, 10_000));
        assertEquals("codec", reducer.state().error);
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);
    }

    @Test public void firstPipelineFailureRemainsVisible() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.pipelineFailed(
                1, "Reserva de armazenamento indisponível"));

        reducer.apply(ServiceStateReducer.Event.pipelineFailed(2, "Muxer is closed"));

        assertEquals("Reserva de armazenamento indisponível", reducer.state().error);
    }

    @Test public void disablingRemoteLiveStopsParkedRecording() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.settings(1, true, true, true, true, null));
        reducer.apply(ServiceStateReducer.Event.viewerConnected(2, 100_000));
        java.util.List<ServiceStateReducer.Effect> effects = reducer.apply(
                ServiceStateReducer.Event.settings(3, true, true, false, true, null));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);
        assertFalse(reducer.state().parkedRecording);
        assertTrue(effects.stream().anyMatch(effect -> effect.type == ServiceStateReducer.EffectType.STOP_RECORDING));
    }

    @Test public void suppliedStopTimesReachFinalizationEffects() {
        ServiceStateReducer automatic = new ServiceStateReducer();
        automatic.apply(ServiceStateReducer.Event.startAutomatic("10", 1));
        java.util.List<ServiceStateReducer.Effect> automaticEffects = automatic.apply(
                ServiceStateReducer.Event.stopAutomatic("10", 9_999, 2));
        ServiceStateReducer.Effect automaticEnd = automaticEffects.stream()
                .filter(effect -> effect.type == ServiceStateReducer.EffectType.FINALIZE_TRIP)
                .findFirst().orElseThrow();
        assertEquals(9_999, automaticEnd.endMs);
        ServiceStateReducer.Effect automaticStop = automaticEffects.stream()
                .filter(effect -> effect.type == ServiceStateReducer.EffectType.STOP_RECORDING)
                .findFirst().orElseThrow();
        assertEquals(9_999, automaticStop.endMs);

        ServiceStateReducer manual = new ServiceStateReducer();
        manual.apply(ServiceStateReducer.Event.startManual("20", 1));
        ServiceStateReducer.Effect manualEnd = manual.apply(
                ServiceStateReducer.Event.stopManual(8_888, 2)).stream()
                .filter(effect -> effect.type == ServiceStateReducer.EffectType.STOP_RECORDING)
                .findFirst().orElseThrow();
        assertEquals(8_888, manualEnd.endMs);
    }
}
