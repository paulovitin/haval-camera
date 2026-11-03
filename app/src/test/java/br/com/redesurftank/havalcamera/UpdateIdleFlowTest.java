package br.com.redesurftank.havalcamera;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real flow + reducer + frame fanout; Android dialogs and clock are controlled boundaries. */
public class UpdateIdleFlowTest {
    @Test public void busyOffersViewingThenWaitsForRecordingBeforeInstallerHandoff() {
        for (String kind : new String[]{"automatic", "manual", "parked"}) {
            Session session = new Session(kind);
            UpdateIdleFlow flow = new UpdateIdleFlow(session);
            int[] installs = {0};
            flow.whenIdle(() -> { assertTrue(session.isIdle()); installs[0]++; });
            assertNotNull("Offer to end viewing: " + kind, session.accept);
            assertEquals(0, session.closedViews);
            assertEquals(0, installs[0]);
            session.accept.run();
            assertEquals(1, session.closedViews);
            assertEquals(0, session.frames.previewSubscriberCount());
            assertEquals(ServiceStateReducer.Mode.RECORDING, session.reducer.state().mode);
            assertEquals(session.trip, session.reducer.state().tripId);
            session.frames.publish(new byte[]{1});
            assertEquals("Encoder still receives frames", 1, session.encoded);
            assertEquals("Viewing receives no frames", 0, session.previewed);
            assertEquals(1, session.waiting);
            assertTrue(flow.isPending());
            // Even past the old viewer-loss grace period, the update must not stop parked recording.
            session.apply(ServiceStateReducer.Event.tick(++session.sequence, 20_000));
            for (int n=0; n<3; n++) {
                assertNotNull(session.poll); session.poll.run();
                assertEquals(session.trip, session.reducer.state().tripId);
                assertEquals(0, installs[0]);
            }
            assertTrue("No stop, pause, restart or finalization emitted by closing viewing", session.effects.stream()
                    .noneMatch(e -> e.type == ServiceStateReducer.EffectType.STOP_RECORDING
                            || e.type == ServiceStateReducer.EffectType.PAUSE_RECORDING
                            || e.type == ServiceStateReducer.EffectType.START_RECORDING
                            || e.type == ServiceStateReducer.EffectType.FINALIZE_TRIP));
            // External trip owner ends recording; the updater has no recording-stop operation.
            session.finishRecording();
            Runnable lastPoll = session.poll;
            lastPoll.run(); lastPoll.run();
            assertEquals(1, installs[0]);
            assertFalse(flow.isPending()); assertNull(session.poll);
        }
    }
    @Test public void dismissesViewingOnlyWithoutStartingOrStoppingRecording() {
        Session session = new Session("none");
        session.endViewing();
        assertEquals(0, session.frames.previewSubscriberCount());
        assertEquals(ServiceStateReducer.Mode.IDLE, session.reducer.state().mode);
        assertTrue(session.effects.stream().noneMatch(e -> e.type == ServiceStateReducer.EffectType.STOP_RECORDING
                || e.type == ServiceStateReducer.EffectType.START_RECORDING));
    }
    @Test public void idleRunsOnceWithoutClosingViewingOrPolling() {
        Session session = new Session("idle"); UpdateIdleFlow flow = new UpdateIdleFlow(session);
        int[] calls = {0}; flow.whenIdle(() -> calls[0]++);
        assertEquals(1, calls[0]); assertEquals(0, session.closedViews);
        assertNull(session.accept); assertNull(session.poll); assertFalse(flow.isPending());
    }
    @Test public void cancellationInvalidatesOfferAndQueuedPoll() {
        Session session = new Session("automatic"); UpdateIdleFlow flow = new UpdateIdleFlow(session);
        int[] installs = {0}; flow.whenIdle(() -> installs[0]++);
        assertNotNull(session.accept); Runnable oldAccept = session.accept;
        session.cancel.run(); oldAccept.run();
        assertEquals(0, session.closedViews); assertEquals(0, installs[0]);
        flow.whenIdle(() -> installs[0]++); session.accept.run();
        Runnable oldPoll = session.poll; assertNotNull(oldPoll);
        flow.cancel(); session.finishRecording(); oldPoll.run();
        assertEquals(0, installs[0]); assertNull(session.poll); assertFalse(flow.isPending());
    }
    @Test public void disconnectedServiceCannotCompleteWait() {
        Session session = new Session("manual"); UpdateIdleFlow flow = new UpdateIdleFlow(session);
        int[] installs = {0}; flow.whenIdle(() -> installs[0]++);
        assertNotNull(session.accept); session.accept.run();
        session.finishRecording(); session.connected=false; session.poll.run();
        assertEquals(0, installs[0]); assertTrue(flow.isPending());
        session.connected=true; session.poll.run(); assertEquals(1, installs[0]);
    }
    @Test public void busyAgainBeforeInstallOffersAndWaitsAgain() {
        Session session = new Session("idle"); UpdateIdleFlow flow = new UpdateIdleFlow(session);
        int[] downloads = {0}, installs = {0};
        flow.whenIdle(() -> downloads[0]++); assertEquals(1, downloads[0]);
        session.apply(ServiceStateReducer.Event.startManual("77", ++session.sequence));
        flow.whenIdle(() -> installs[0]++);
        assertNotNull(session.accept); session.accept.run();
        session.poll.run(); assertEquals(0, installs[0]);
        assertEquals("77", session.reducer.state().tripId);
        session.finishRecording(); session.poll.run(); assertEquals(1, installs[0]);
    }
    @Test public void failedViewingCloseNeverContinues() {
        Session session = new Session("manual") {
            @Override public void endViewing() { throw new IllegalStateException("close failed"); }
        };
        UpdateIdleFlow flow = new UpdateIdleFlow(session); int[] installs = {0};
        flow.whenIdle(() -> installs[0]++); assertNotNull(session.accept); session.accept.run();
        assertEquals(0, installs[0]); assertNotNull(session.error);
        assertFalse(flow.isPending()); assertNull(session.poll);
    }
    static class Session implements UpdateIdleFlow.Host {
        final ServiceStateReducer reducer = new ServiceStateReducer();
        final FrameRouter frames = new FrameRouter();
        final List<ServiceStateReducer.Effect> effects = new ArrayList<>();
        long sequence; String trip, error;
        boolean connected=true;
        int encoded, previewed, closedViews, waiting;
        Runnable accept, cancel, poll;
        Session(String kind) {
            if (kind.equals("idle")) return;
            if (kind.equals("parked")) apply(ServiceStateReducer.Event.settings(++sequence,true,true,true,true,null));
            if (kind.equals("manual")) apply(ServiceStateReducer.Event.startManual("77",++sequence));
            if (kind.equals("automatic")) apply(ServiceStateReducer.Event.startAutomatic("77",++sequence));
            apply(ServiceStateReducer.Event.viewerConnected(++sequence,1_000));
            trip=reducer.state().tripId;
            frames.subscribe(frame -> previewed++); frames.setEncoder(frame -> encoded++);
            effects.clear();
        }
        void apply(ServiceStateReducer.Event event) { effects.addAll(reducer.apply(event)); }
        void finishRecording() { apply(ServiceStateReducer.Event.stopRecording(++sequence)); frames.setEncoder(null); }
        public boolean isIdle() { return connected && frames.previewSubscriberCount()==0 && reducer.state().mode==ServiceStateReducer.Mode.IDLE; }
        public void endViewing() {
            closedViews++; frames.closePreviews(); apply(ServiceStateReducer.Event.viewerDismissed(++sequence,2_000));
        }
        public void offerEndViewing(Runnable accept, Runnable cancel) { this.accept=accept; this.cancel=cancel; }
        public void waiting(Runnable cancel) { waiting++; this.cancel=cancel; }
        public void schedulePoll(Runnable poll) { this.poll=poll; }
        public void stopPolling() { poll=null; }
        public void cancelled(String reason) { error=reason; }
    }
}
