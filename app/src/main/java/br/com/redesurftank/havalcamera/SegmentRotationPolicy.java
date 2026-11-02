package br.com.redesurftank.havalcamera;

/** Chooses segment boundaries from encoded presentation timestamps. */
public final class SegmentRotationPolicy {
    static final long SEGMENT_DURATION_US = 60_000_000L;

    private long segmentStartPtsUs = -1;

    public boolean shouldRotate(long ptsUs, boolean keyframe) {
        if (ptsUs < 0) throw new IllegalArgumentException("PTS must be non-negative");
        if (segmentStartPtsUs < 0) {
            if (keyframe) segmentStartPtsUs = ptsUs;
            return false;
        }
        if (!keyframe || ptsUs - segmentStartPtsUs < SEGMENT_DURATION_US) return false;
        segmentStartPtsUs = ptsUs;
        return true;
    }
}
