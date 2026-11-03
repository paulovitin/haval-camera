package br.com.redesurftank.havalcamera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Encoder and strict decoder for live AVC messages. */
public final class H264WireCodec {
    public static final int MAGIC = 0x48564331; // HVC1
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 24;
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    public static final int FLAG_KEYFRAME = 1;

    private H264WireCodec() { }

    public enum Type {
        CONFIG(1),
        ACCESS_UNIT(2);

        final int code;

        Type(int code) {
            this.code = code;
        }

        static Type fromCode(int code) {
            for (Type type : values()) if (type.code == code) return type;
            throw new IllegalArgumentException("Unknown HVC1 message type: " + code);
        }
    }

    public static ByteBuffer encodeConfig(long sequence, long ptsUs, byte[] sps, byte[] pps) {
        requireNal(sps, "SPS");
        requireNal(pps, "PPS");
        if (sps.length > 0xffff || pps.length > 0xffff) {
            throw new IllegalArgumentException("CONFIG NAL exceeds u16 length");
        }
        int payloadLength = 4 + sps.length + pps.length;
        requirePayloadLength(payloadLength);
        ByteBuffer result = header(Type.CONFIG, 0, sequence, ptsUs, payloadLength);
        result.putShort((short) sps.length).put(sps).putShort((short) pps.length).put(pps);
        return (ByteBuffer) result.flip();
    }

    public static ByteBuffer encodeAccessUnit(
            long sequence, long ptsUs, boolean keyframe, byte[]... nalUnits) {
        if (nalUnits == null || nalUnits.length == 0) {
            throw new IllegalArgumentException("ACCESS_UNIT requires at least one NAL unit");
        }
        long payloadLength = 0;
        for (byte[] nal : nalUnits) {
            requireNal(nal, "access-unit");
            payloadLength += 4L + nal.length;
        }
        requirePayloadLength(payloadLength);
        ByteBuffer result = header(Type.ACCESS_UNIT, keyframe ? FLAG_KEYFRAME : 0,
                sequence, ptsUs, (int) payloadLength);
        for (byte[] nal : nalUnits) result.putInt(nal.length).put(nal);
        return (ByteBuffer) result.flip();
    }

    public static Frame decode(ByteBuffer encoded) {
        if (encoded == null) throw new IllegalArgumentException("HVC1 frame is null");
        ByteBuffer source = encoded.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (source.remaining() < HEADER_BYTES) throw new IllegalArgumentException("Truncated HVC1 header");
        if (source.getInt() != MAGIC) throw new IllegalArgumentException("Wrong HVC1 magic");
        int version = source.get() & 0xff;
        if (version != VERSION) throw new IllegalArgumentException("Unsupported HVC1 version: " + version);
        Type type = Type.fromCode(source.get() & 0xff);
        int flags = source.getShort() & 0xffff;
        if ((flags & ~FLAG_KEYFRAME) != 0 || (type == Type.CONFIG && flags != 0)) {
            throw new IllegalArgumentException("Invalid HVC1 flags");
        }
        long sequence = Integer.toUnsignedLong(source.getInt());
        long ptsUs = source.getLong();
        if (ptsUs < 0) throw new IllegalArgumentException("PTS must be non-negative");
        int payloadLength = source.getInt();
        requirePayloadLength(payloadLength);
        if (source.remaining() != payloadLength) throw new IllegalArgumentException("HVC1 payload length mismatch");
        byte[] payload = new byte[payloadLength];
        source.get(payload);

        if (type == Type.CONFIG) return decodeConfig(sequence, ptsUs, payload);
        return decodeAccessUnit(sequence, ptsUs, flags, payload);
    }

    private static Frame decodeConfig(long sequence, long ptsUs, byte[] payload) {
        ByteBuffer data = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < 4) throw new IllegalArgumentException("CONFIG is missing SPS/PPS lengths");
        byte[] sps = readU16Nal(data, "SPS");
        byte[] pps = readU16Nal(data, "PPS");
        if (data.hasRemaining()) throw new IllegalArgumentException("Trailing CONFIG bytes");
        return new Frame(Type.CONFIG, sequence, ptsUs, false, payload, sps, pps, new byte[0][]);
    }

    private static Frame decodeAccessUnit(long sequence, long ptsUs, int flags, byte[] payload) {
        ByteBuffer data = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        List<byte[]> units = new ArrayList<>();
        while (data.hasRemaining()) {
            if (data.remaining() < 4) throw new IllegalArgumentException("Truncated AVCC NAL length");
            int length = data.getInt();
            if (length <= 0 || length > data.remaining()) {
                throw new IllegalArgumentException("Invalid AVCC NAL length");
            }
            byte[] nal = new byte[length];
            data.get(nal);
            units.add(nal);
        }
        if (units.isEmpty()) throw new IllegalArgumentException("ACCESS_UNIT contains no NAL units");
        return new Frame(Type.ACCESS_UNIT, sequence, ptsUs, (flags & FLAG_KEYFRAME) != 0,
                payload, null, null, units.toArray(new byte[0][]));
    }

    private static byte[] readU16Nal(ByteBuffer data, String name) {
        if (data.remaining() < 2) throw new IllegalArgumentException("CONFIG is missing " + name);
        int length = data.getShort() & 0xffff;
        if (length == 0 || length > data.remaining()) {
            throw new IllegalArgumentException("Invalid CONFIG " + name + " length");
        }
        byte[] nal = new byte[length];
        data.get(nal);
        return nal;
    }

    private static ByteBuffer header(
            Type type, int flags, long sequence, long ptsUs, int payloadLength) {
        if (sequence < 0 || sequence > 0xffff_ffffL) {
            throw new IllegalArgumentException("Sequence must fit u32");
        }
        if (ptsUs < 0) throw new IllegalArgumentException("PTS must be non-negative");
        return ByteBuffer.allocate(HEADER_BYTES + payloadLength).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .put((byte) VERSION)
                .put((byte) type.code)
                .putShort((short) flags)
                .putInt((int) sequence)
                .putLong(ptsUs)
                .putInt(payloadLength);
    }

    private static void requireNal(byte[] nal, String name) {
        if (nal == null || nal.length == 0) {
            throw new IllegalArgumentException(name + " NAL must not be empty");
        }
    }

    private static void requirePayloadLength(long payloadLength) {
        if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("HVC1 payload must contain 1 to 4194304 bytes");
        }
    }

    public static final class Frame {
        public final Type type;
        public final long sequence;
        public final long ptsUs;
        public final boolean keyframe;
        public final byte[] payload;
        public final byte[] sps;
        public final byte[] pps;
        public final byte[][] nalUnits;

        private Frame(Type type, long sequence, long ptsUs, boolean keyframe, byte[] payload,
                byte[] sps, byte[] pps, byte[][] nalUnits) {
            this.type = type;
            this.sequence = sequence;
            this.ptsUs = ptsUs;
            this.keyframe = keyframe;
            this.payload = payload;
            this.sps = sps;
            this.pps = pps;
            this.nalUnits = nalUnits;
        }
    }
}
