package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
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
}
