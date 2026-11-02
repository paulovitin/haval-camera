package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SegmentRotationPolicyTest {
    @Test
    public void rotatesAtSixtySecondsFromFirstWrittenKeyframe() {
        SegmentRotationPolicy policy = new SegmentRotationPolicy();

        assertFalse(policy.shouldRotate(10_000, false));
        assertFalse(policy.shouldRotate(20_000, true));
        assertFalse(policy.shouldRotate(60_019_999, true));
        assertTrue(policy.shouldRotate(60_020_000, true));
    }

    @Test
    public void waitsForKeyframeAfterDurationThreshold() {
        SegmentRotationPolicy policy = new SegmentRotationPolicy();

        assertFalse(policy.shouldRotate(0, true));
        assertFalse(policy.shouldRotate(60_000_000, false));
        assertFalse(policy.shouldRotate(60_500_000, false));
        assertTrue(policy.shouldRotate(61_000_000, true));
    }

    @Test
    public void rotationKeyframeStartsNextPtsWindow() {
        SegmentRotationPolicy policy = new SegmentRotationPolicy();

        assertFalse(policy.shouldRotate(1_000_000, true));
        assertTrue(policy.shouldRotate(61_000_000, true));
        assertFalse(policy.shouldRotate(120_999_999, true));
        assertTrue(policy.shouldRotate(121_000_000, true));
    }

    @Test
    public void wallClockIsNotAnInputToRotation() {
        SegmentRotationPolicy early = new SegmentRotationPolicy();
        SegmentRotationPolicy late = new SegmentRotationPolicy();

        assertFalse(early.shouldRotate(5_000_000, true));
        assertFalse(late.shouldRotate(5_000_000, true));
        assertTrue(early.shouldRotate(65_000_000, true));
        assertTrue(late.shouldRotate(65_000_000, true));
    }
}
