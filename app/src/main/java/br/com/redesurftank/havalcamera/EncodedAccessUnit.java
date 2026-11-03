package br.com.redesurftank.havalcamera;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Immutable encoded AVC output detached from MediaCodec's reusable buffer. */
public final class EncodedAccessUnit {
    private final byte[] data;
    public final long presentationTimeUs;
    public final boolean keyframe;
    public final boolean endOfStream;

    public EncodedAccessUnit(
            byte[] data, long presentationTimeUs, boolean keyframe, boolean endOfStream) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Empty access unit");
        if (presentationTimeUs < 0) throw new IllegalArgumentException("Negative presentation time");
        this.data = Arrays.copyOf(data, data.length);
        this.presentationTimeUs = presentationTimeUs;
        this.keyframe = keyframe;
        this.endOfStream = endOfStream;
    }

    public int size() {
        return data.length;
    }

    public ByteBuffer data() {
        return ByteBuffer.wrap(data).asReadOnlyBuffer();
    }
}
