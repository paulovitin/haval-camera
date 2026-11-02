package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RetryableStartEffectTest {
    @Test public void failedEffectAfterJournalPersistenceCanBeRetriedWithANewRequest() throws Exception {
        CommandJournal journal = new CommandJournal(new CommandJournalTest.MemoryPreferences());
        ServiceStateReducer reducer = new ServiceStateReducer();
        AtomicInteger starts = new AtomicInteger();

        assertEquals(CommandJournal.Outcome.APPLIED,
                journal.apply(1, "start-1", VideoContract.Command.START_TRIP, "100").outcome);
        assertThrows(IOException.class, () -> RetryableStartEffect.apply(
                reducer, ServiceStateReducer.Event.startAutomatic("100", 1), 2, effect -> {
                    if (effect.type == ServiceStateReducer.EffectType.START_RECORDING
                            && starts.getAndIncrement() == 0) throw new IOException("codec");
                }));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);

        CommandJournal.Decision retry =
                journal.apply(2, "start-2", VideoContract.Command.START_TRIP, "100");
        assertEquals(CommandJournal.Outcome.NO_OP, retry.outcome);
        RetryableStartEffect.apply(
                reducer, ServiceStateReducer.Event.startAutomatic("100", 3), 4, effect -> {
                    if (effect.type == ServiceStateReducer.EffectType.START_RECORDING)
                        starts.incrementAndGet();
                });

        assertEquals(2, starts.get());
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        assertEquals(CommandJournal.Outcome.DUPLICATE,
                journal.apply(3, "start-2", VideoContract.Command.START_TRIP, "100").outcome);
    }

    @Test public void failedStartupRecoveryIsRetriedByANewReconcile() throws Exception {
        CommandJournal journal = new CommandJournal(new CommandJournalTest.MemoryPreferences());
        ServiceStateReducer reducer = new ServiceStateReducer();
        AtomicInteger starts = new AtomicInteger();
        journal.apply(1, "start-1", VideoContract.Command.START_TRIP, "100");

        assertThrows(IOException.class, () -> RetryableStartEffect.apply(reducer,
                ServiceStateReducer.Event.settings(1, true, false, true, true,
                        journal.activeTripId()), 2, effect -> {
                    if (effect.type == ServiceStateReducer.EffectType.START_RECORDING) {
                        starts.incrementAndGet();
                        throw new IOException("catalog");
                    }
                }));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);
        assertEquals("100", journal.activeTripId());

        assertEquals(CommandJournal.Outcome.NO_OP,
                journal.apply(2, "reconcile-2", VideoContract.Command.RECONCILE, "100").outcome);
        RetryableStartEffect.apply(reducer,
                ServiceStateReducer.Event.startAutomatic("100", 3), 4, effect -> {
                    if (effect.type == ServiceStateReducer.EffectType.START_RECORDING)
                        starts.incrementAndGet();
                });

        assertEquals(2, starts.get());
        assertEquals(ServiceStateReducer.Mode.RECORDING, reducer.state().mode);
        assertEquals(CommandJournal.Outcome.DUPLICATE,
                journal.apply(3, "reconcile-2", VideoContract.Command.RECONCILE, "100").outcome);
    }

    @Test public void everyServiceEventUsesTheRetryableStartBoundary() throws Exception {
        Path sourceRoot = Files.exists(Path.of("src/main/AndroidManifest.xml"))
                ? Path.of("src/main") : Path.of("app/src/main");
        String service = new String(Files.readAllBytes(sourceRoot.resolve(
                "java/br/com/redesurftank/havalcamera/TripVideoService.java")),
                StandardCharsets.UTF_8);

        assertTrue(service.contains(
                "private void apply(ServiceStateReducer.Event event) throws Exception {\n"
                + "        RetryableStartEffect.apply(reducer, event, nextSequence(), this::execute);"));
    }

    @Test public void failedLiveOnlyCaptureCanBeRetriedWithFreshCredentials() throws Exception {
        ServiceStateReducer reducer = new ServiceStateReducer();
        AtomicInteger starts = new AtomicInteger();

        assertThrows(IOException.class, () -> RetryableStartEffect.apply(reducer,
                ServiceStateReducer.Event.viewerConnected(1, 1_000), 2, effect -> {
                    if (effect.type == ServiceStateReducer.EffectType.START_CAPTURE) {
                        starts.incrementAndGet();
                        throw new IOException("camera unavailable");
                    }
                }));
        assertEquals(ServiceStateReducer.Mode.IDLE, reducer.state().mode);

        RetryableStartEffect.apply(reducer,
                ServiceStateReducer.Event.viewerConnected(3, 2_000), 4, effect -> {
                    if (effect.type == ServiceStateReducer.EffectType.START_CAPTURE)
                        starts.incrementAndGet();
                });

        assertEquals(2, starts.get());
        assertEquals(ServiceStateReducer.Mode.LIVE_ONLY, reducer.state().mode);
    }

    @Test public void nonStartEffectFailureDoesNotBecomeAPipelineStartFailure() {
        ServiceStateReducer reducer = new ServiceStateReducer();
        reducer.apply(ServiceStateReducer.Event.startAutomatic("100", 1));

        assertThrows(IOException.class, () -> RetryableStartEffect.apply(reducer,
                ServiceStateReducer.Event.settings(2, false, false, true, true, "100"),
                3, effect -> { throw new IOException("pause"); }));

        assertNull(reducer.state().error);
    }
}
