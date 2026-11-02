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
