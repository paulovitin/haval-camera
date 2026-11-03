package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CommandAcknowledgementTest {
    @Test public void exportedResultContractIsExact() {
        assertEquals("br.com.redesurftank.havalcamera.action.COMMAND_RESULT",
                VideoContract.ACTION_COMMAND_RESULT);
        assertEquals("br.com.redesurftank.havalcamera.extra.COMMAND_STATUS",
                VideoContract.EXTRA_COMMAND_STATUS);
        assertEquals("br.com.redesurftank.havalcamera.extra.CAMERA_REVISION",
                VideoContract.EXTRA_CAMERA_REVISION);
        assertEquals("br.com.redesurftank.havalcamera.extra.ERROR_MESSAGE",
                VideoContract.EXTRA_ERROR_MESSAGE);
        assertEquals("APPLIED", VideoContract.STATUS_APPLIED);
        assertEquals("NO_OP", VideoContract.STATUS_NO_OP);
        assertEquals("STALE", VideoContract.STATUS_STALE);
        assertEquals("REJECTED", VideoContract.STATUS_REJECTED);
        assertEquals("ERROR", VideoContract.STATUS_ERROR);
    }

    @Test public void journalOutcomesMapToBoundedWireStatuses() {
        assertEquals(VideoContract.STATUS_APPLIED,
                CommandAcknowledgement.statusFor(CommandJournal.Outcome.APPLIED));
        assertEquals(VideoContract.STATUS_NO_OP,
                CommandAcknowledgement.statusFor(CommandJournal.Outcome.NO_OP));
        assertEquals(VideoContract.STATUS_REJECTED,
                CommandAcknowledgement.statusFor(CommandJournal.Outcome.DUPLICATE));
        assertEquals(VideoContract.STATUS_STALE,
                CommandAcknowledgement.statusFor(CommandJournal.Outcome.STALE_REVISION));
        assertEquals(VideoContract.STATUS_REJECTED,
                CommandAcknowledgement.statusFor(CommandJournal.Outcome.STALE_TRIP));
    }
}
