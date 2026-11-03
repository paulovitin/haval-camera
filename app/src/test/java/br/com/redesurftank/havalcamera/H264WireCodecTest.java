package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class H264WireCodecTest {
    @Test
    public void configSpsAndPpsRoundTripWithCanonicalFixture() {
        byte[] sps = bytes(0x67, 0x42, 0x00, 0x1f);
        byte[] pps = bytes(0x68, 0xce, 0x06, 0xe2);

        ByteBuffer encoded = H264WireCodec.encodeConfig(1, 0, sps, pps);
        H264WireCodec.Frame decoded = H264WireCodec.decode(encoded);

        assertEquals("48564331010100000000000100000000000000000000000c"
                + "00046742001f000468ce06e2", hex(encoded));
        assertEquals(H264WireCodec.Type.CONFIG, decoded.type);
        assertEquals(1, decoded.sequence);
        assertEquals(0, decoded.ptsUs);
        assertFalse(decoded.keyframe);
        assertArrayEquals(sps, decoded.sps);
        assertArrayEquals(pps, decoded.pps);
    }

    @Test
    public void accessUnitUsesAvccAndCarriesKeyframeFlag() {
        ByteBuffer encoded = H264WireCodec.encodeAccessUnit(
                2, 66_667, true, bytes(0x65, 0x88, 0x84));
        H264WireCodec.Frame decoded = H264WireCodec.decode(encoded);

        assertEquals("485643310102000100000002000000000001046b00000007"
                + "00000003658884", hex(encoded));
        assertEquals(H264WireCodec.Type.ACCESS_UNIT, decoded.type);
        assertTrue(decoded.keyframe);
        assertEquals(2, decoded.sequence);
        assertEquals(66_667, decoded.ptsUs);
        assertEquals(1, decoded.nalUnits.length);
        assertArrayEquals(bytes(0x65, 0x88, 0x84), decoded.nalUnits[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedPayloadLengthIsRejected() {
        ByteBuffer encoded = H264WireCodec.encodeAccessUnit(1, 0, false, bytes(0x41));
        encoded.putInt(20, 10);
        H264WireCodec.decode(encoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongMagicIsRejected() {
        ByteBuffer encoded = H264WireCodec.encodeAccessUnit(1, 0, false, bytes(0x41));
        encoded.putInt(0, 0x12345678);
        H264WireCodec.decode(encoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongVersionIsRejected() {
        ByteBuffer encoded = H264WireCodec.encodeAccessUnit(1, 0, false, bytes(0x41));
        encoded.put(4, (byte) 2);
        H264WireCodec.decode(encoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingSpsIsRejected() {
        H264WireCodec.encodeConfig(1, 0, new byte[0], bytes(0x68));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingPpsIsRejected() {
        H264WireCodec.encodeConfig(1, 0, bytes(0x67), new byte[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedPayloadIsRejected() {
        H264WireCodec.encodeAccessUnit(1, 0, false,
                new byte[H264WireCodec.MAX_PAYLOAD_BYTES]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedAvccNalLengthIsRejected() {
        ByteBuffer frame = ByteBuffer.allocate(H264WireCodec.HEADER_BYTES + 5)
                .order(ByteOrder.BIG_ENDIAN);
        frame.putInt(H264WireCodec.MAGIC).put((byte) H264WireCodec.VERSION)
                .put((byte) H264WireCodec.Type.ACCESS_UNIT.code).putShort((short) 0)
                .putInt(1).putLong(0).putInt(5).putInt(2).put((byte) 0x41).flip();
        H264WireCodec.decode(frame);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static String hex(ByteBuffer buffer) {
        StringBuilder result = new StringBuilder();
        ByteBuffer copy = buffer.duplicate();
        copy.position(0);
        while (copy.hasRemaining()) result.append(String.format("%02x", copy.get() & 0xff));
        return result.toString();
    }
}
