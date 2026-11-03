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

        assertEquals(2, syncRequests.get());
        assertTrue(queue.isRecovering());
        assertFalse(queue.offerAccessUnit(bytes(40), false));
        assertEquals(2, syncRequests.get());

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

    @Test public void changedConfigDropsOldAccessUnitsUntilANewKeyframe() {
        AtomicInteger syncRequests = new AtomicInteger();
        BoundedFrameQueue queue = new BoundedFrameQueue(100, 10, syncRequests::incrementAndGet);
        queue.offerConfig(bytes(1));
        queue.offerAccessUnit(bytes(2), true);
        queue.offerAccessUnit(bytes(3), false);

        queue.offerConfig(bytes(9));

        assertTrue(queue.isRecovering());
        assertEquals(2, syncRequests.get());
        BoundedFrameQueue.Message config = queue.poll();
        assertTrue(config.config);
        assertArrayEquals(bytes(9), config.bytes());
        assertNull(queue.poll());
        assertFalse(queue.offerAccessUnit(bytes(4), false));
        assertTrue(queue.offerAccessUnit(bytes(5), true));
    }

    @Test public void initialConfigAlwaysWaitsForKeyframe() {
        AtomicInteger syncRequests = new AtomicInteger();
        BoundedFrameQueue queue = new BoundedFrameQueue(100, 10, syncRequests::incrementAndGet);

        assertTrue(queue.offerConfig(bytes(1)));

        assertTrue(queue.isRecovering());
        assertEquals(1, syncRequests.get());
        assertFalse(queue.offerAccessUnit(bytes(2), false));
        assertTrue(queue.offerAccessUnit(bytes(3), true));
        assertFalse(queue.isRecovering());
    }

    @Test public void transportBytesShareTheSameOneMibBudget() {
        BoundedFrameQueue queue = new BoundedFrameQueue(() -> { });
        byte[] frame = new byte[32];

        assertFalse(queue.offerAccessUnit(frame, false,
                BoundedFrameQueue.MAX_BYTES - frame.length + 1L));

        assertEquals(0, queue.byteSize());
        assertTrue(queue.isRecovering());
    }

    @Test public void exactCombinedByteLimitIsAcceptedButOneByteOverRecovers() {
        AtomicInteger syncRequests = new AtomicInteger();
        BoundedFrameQueue queue = new BoundedFrameQueue(10, 30, syncRequests::incrementAndGet);

        assertTrue(queue.offerConfig(bytes(1, 2, 3, 4), 6));
        assertEquals(10, 6 + queue.byteSize());
        assertFalse(queue.offerAccessUnit(bytes(5), true, 10));
        assertTrue(queue.isRecovering());
        assertEquals(1, syncRequests.get());
    }

    @Test public void busyTransportConsumesOneOfTheThirtyMessageSlots() {
        BoundedFrameQueue queue = new BoundedFrameQueue(100, 3, () -> { });

        assertTrue(queue.offerConfig(bytes(1), 1));
        assertTrue(queue.offerAccessUnit(bytes(2), true, 1));
        assertFalse(queue.offerAccessUnit(bytes(3), false, 1));

        assertEquals(2, queue.size());
        assertTrue(queue.isRecovering());
    }

    @Test public void oversizedTransportAlwaysEntersRecoveryAndRequestsSync() {
        AtomicInteger syncRequests = new AtomicInteger();
        BoundedFrameQueue queue = new BoundedFrameQueue(10, 3, syncRequests::incrementAndGet);

        assertFalse(queue.offerAccessUnit(bytes(1), false, 11));

        assertTrue(queue.isRecovering());
        assertEquals(1, syncRequests.get());
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
