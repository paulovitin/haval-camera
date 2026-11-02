package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class BoundedFrameQueueTest {
    @Test public void pressureKeepsConfigAndNewestKeyframeAndRequestsOneSync() {
        AtomicInteger syncRequests = new AtomicInteger();
        BoundedFrameQueue queue = new BoundedFrameQueue(30, 4, syncRequests::incrementAndGet);

        assertTrue(queue.offerConfig(bytes(1, 2, 3, 4, 5)));
        assertTrue(queue.offerAccessUnit(bytes(10, 11, 12, 13, 14, 15, 16, 17, 18, 19), true));
        assertTrue(queue.offerAccessUnit(bytes(20, 21, 22, 23, 24, 25, 26, 27, 28, 29), false));
        assertFalse(queue.offerAccessUnit(bytes(30, 31, 32, 33, 34, 35, 36, 37, 38, 39), false));

        assertEquals(1, syncRequests.get());
        assertTrue(queue.isRecovering());
        assertFalse(queue.offerAccessUnit(bytes(40), false));
        assertEquals(1, syncRequests.get());

        byte[] newestKeyframe = bytes(50, 51, 52);
        assertTrue(queue.offerAccessUnit(newestKeyframe, true));
        assertFalse(queue.isRecovering());
        assertTrue(queue.poll().config);
        BoundedFrameQueue.Message keyframe = queue.poll();
        assertTrue(keyframe.keyframe);
        assertArrayEquals(newestKeyframe, keyframe.bytes());
        assertNull(queue.poll());
    }

    @Test public void frameAndByteLimitsApplyWhicheverComesFirst() {
        BoundedFrameQueue byFrames = new BoundedFrameQueue(1_024, 3, () -> { });
        byFrames.offerConfig(bytes(1));
        byFrames.offerAccessUnit(bytes(2), true);
        byFrames.offerAccessUnit(bytes(3), false);
        assertFalse(byFrames.offerAccessUnit(bytes(4), false));
        assertTrue(byFrames.size() <= 3);

        BoundedFrameQueue byBytes = new BoundedFrameQueue(6, 30, () -> { });
        byBytes.offerConfig(bytes(1, 2));
        byBytes.offerAccessUnit(bytes(3, 4), true);
        assertFalse(byBytes.offerAccessUnit(bytes(5, 6, 7), false));
        assertTrue(byBytes.byteSize() <= 6);
    }

    @Test public void clearRemovesSessionDataAndRecoveryState() {
        BoundedFrameQueue queue = new BoundedFrameQueue(4, 2, () -> { });
        queue.offerConfig(bytes(1, 2));
        queue.offerAccessUnit(bytes(3, 4), true);
        queue.offerAccessUnit(bytes(5), false);
        assertTrue(queue.isRecovering());

        queue.clear();

        assertEquals(0, queue.size());
        assertEquals(0, queue.byteSize());
        assertFalse(queue.isRecovering());
        assertNull(queue.poll());
    }

    @Test public void productionLimitsAreOneMibAndThirtyMessages() {
        assertEquals(1024 * 1024, BoundedFrameQueue.MAX_BYTES);
        assertEquals(30, BoundedFrameQueue.MAX_MESSAGES);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }
}
