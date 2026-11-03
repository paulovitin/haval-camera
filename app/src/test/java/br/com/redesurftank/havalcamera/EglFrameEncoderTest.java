package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EglFrameEncoderTest {
    @Test
    public void stoppingFrameSubmissionKeepsDrainAliveUntilCodecEos() {
        EglFrameEncoder.EncoderLifecycle lifecycle = new EglFrameEncoder.EncoderLifecycle();
        lifecycle.start();

        lifecycle.beginDrain();

        assertFalse(lifecycle.acceptingFrames());
        assertTrue(lifecycle.drainingOutput());

        lifecycle.endOfStream();

        assertFalse(lifecycle.drainingOutput());
    }

    @Test
    public void submittedPresentationTimesMustStrictlyIncrease() {
        EglFrameEncoder.EncoderLifecycle lifecycle = new EglFrameEncoder.EncoderLifecycle();
        lifecycle.start();

        lifecycle.acceptPresentationTime(100);
        lifecycle.acceptPresentationTime(101);

        assertThrows(IllegalArgumentException.class,
                () -> lifecycle.acceptPresentationTime(101));
        assertThrows(IllegalArgumentException.class,
                () -> lifecycle.acceptPresentationTime(99));
    }

    @Test
    public void framesAreRejectedAfterDrainBegins() {
        EglFrameEncoder.EncoderLifecycle lifecycle = new EglFrameEncoder.EncoderLifecycle();
        lifecycle.start();
        lifecycle.beginDrain();

        assertThrows(IllegalStateException.class,
                () -> lifecycle.acceptPresentationTime(1));
    }
}
