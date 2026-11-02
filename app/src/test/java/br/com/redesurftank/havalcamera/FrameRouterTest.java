package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class FrameRouterTest {
    @Test public void serviceOwnedFrameFeedsEncoderAndPreviewSubscribers() {
        FrameRouter router = new FrameRouter();
        byte[] frame = {1, 2, 3};
        byte[][] encoded = new byte[1][];
        byte[][] previewed = new byte[1][];
        router.setEncoder(value -> encoded[0] = value);
        FrameRouter.Subscription subscription = router.subscribe(value -> previewed[0] = value);
        router.publish(frame);
        assertArrayEquals(frame, encoded[0]);
        assertArrayEquals(frame, previewed[0]);
        subscription.close();
        assertEquals(0, router.previewSubscriberCount());
    }
}
