package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.Test;

public final class TransferChunkCodecTest {
    private static final String SHA_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String ZERO_SHA =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String DATA = "485654310101000000000001000000000000000000000003"
            + SHA_ABC + "616263";
    private static final String EOF = "485654310102000000000001000000000000000300000000"
            + SHA_ABC;
    private static final String ACK = "485654310103000000000001000000000000000300000000"
            + ZERO_SHA;
    private static final String ERROR = "485654310104000000000001000000000000000000000003"
            + SHA_ABC + "616263";

    @Test
    public void dataMatchesCanonicalFixtureAndDecodesIndependently() {
        assertEquals(DATA, hex(TransferChunkCodec.encodeData(1, 0, abc())));
        assertMessage(DATA, TransferChunkCodec.Type.DATA, 1, 0, abc(), SHA_ABC);
    }

    @Test
    public void eofMatchesCanonicalFixtureAndKeepsWholeSegmentDigest() {
        assertEquals(EOF, hex(TransferChunkCodec.encodeEof(1, 3, bytes(SHA_ABC))));
        assertMessage(EOF, TransferChunkCodec.Type.EOF, 1, 3, new byte[0], SHA_ABC);
    }

    @Test
    public void ackMatchesCanonicalFixtureAndMeansFirstUnpersistedByte() {
        assertEquals(ACK, hex(TransferChunkCodec.encodeAck(1, 3)));
        assertMessage(ACK, TransferChunkCodec.Type.ACK, 1, 3, new byte[0], ZERO_SHA);
    }

    @Test
    public void errorMatchesCanonicalFixtureAndHashesUtf8Payload() {
        assertEquals(ERROR, hex(TransferChunkCodec.encodeError(1, "abc")));
        assertMessage(ERROR, TransferChunkCodec.Type.ERROR, 1, 0, abc(), SHA_ABC);
    }

    @Test
    public void ordinalPreservesEveryUnsignedIntBitPattern() {
        for (int ordinal : new int[] {0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            assertEquals(Integer.toUnsignedLong(ordinal), TransferChunkCodec.decode(
                    TransferChunkCodec.encodeData(ordinal, 0, abc())).ordinal);
            assertEquals(Integer.toUnsignedLong(ordinal), TransferChunkCodec.decode(
                    TransferChunkCodec.encodeEof(ordinal, 0, bytes(SHA_ABC))).ordinal);
            assertEquals(Integer.toUnsignedLong(ordinal), TransferChunkCodec.decode(
                    TransferChunkCodec.encodeAck(ordinal, 0)).ordinal);
            assertEquals(Integer.toUnsignedLong(ordinal), TransferChunkCodec.decode(
                    TransferChunkCodec.encodeError(ordinal, "abc")).ordinal);
        }
        assertEquals("ffffffff", hex(TransferChunkCodec.encodeAck(-1, 0)).substring(16, 24));
    }

    @Test
    public void offsetsPreserveBigEndian64BitValuesWithoutWrapping() {
        long offset = 0x0102030405060708L;
        assertEquals("0102030405060708",
                hex(TransferChunkCodec.encodeData(1, offset, abc())).substring(24, 40));
        assertEquals(offset, TransferChunkCodec.decode(
                TransferChunkCodec.encodeData(1, offset, abc())).offset);
        assertEquals(Long.MAX_VALUE, TransferChunkCodec.decode(
                TransferChunkCodec.encodeAck(1, Long.MAX_VALUE)).offset);
        assertEquals(Long.MAX_VALUE, TransferChunkCodec.decode(
                TransferChunkCodec.encodeEof(1, Long.MAX_VALUE, bytes(SHA_ABC))).offset);
        assertEquals(Long.MAX_VALUE - 3, TransferChunkCodec.decode(
                TransferChunkCodec.encodeData(1, Long.MAX_VALUE - 3, abc())).offset);
    }

    @Test
    public void negativeOffsetsAreRejectedByEveryOffsetEncoder() {
        for (long offset : new long[] {-1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TransferChunkCodec.encodeData(1, offset, abc()));
            assertThrows(IllegalArgumentException.class,
                    () -> TransferChunkCodec.encodeEof(1, offset, bytes(SHA_ABC)));
            assertThrows(IllegalArgumentException.class,
                    () -> TransferChunkCodec.encodeAck(1, offset));
        }
    }

    @Test
    public void highBitUnsignedOffsetsAreRejectedBeforeArithmetic() {
        for (String fixture : new String[] {DATA, EOF, ACK, ERROR}) {
            ByteBuffer encoded = frame(fixture);
            encoded.putLong(12, Long.MIN_VALUE);
            reject(encoded);
        }
    }

    @Test
    public void dataEndOffsetOverflowIsRejectedOnEncodeAndDecode() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeData(1, Long.MAX_VALUE - 2, abc()));
        ByteBuffer encoded = frame(DATA);
        encoded.putLong(12, Long.MAX_VALUE - 2);
        reject(encoded);
    }

    @Test
    public void fullSizeDataChunkIsAccepted() {
        byte[] payload = new byte[262144];
        payload[0] = 1;
        payload[payload.length - 1] = 2;
        ByteBuffer encoded = TransferChunkCodec.encodeData(1, 0, payload);
        assertEquals(56 + 262144, encoded.remaining());
        assertArrayEquals(payload, TransferChunkCodec.decode(encoded).payload);
    }

    @Test
    public void oversizedDataIsRejectedBeforeAllocationOrDigest() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeData(1, 0, new byte[262145]));
        reject(rawPayload(1, new byte[262145]));
    }

    @Test
    public void emptyOrNullDataIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeData(1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeData(1, 0, null));
        reject(rawPayload(1, new byte[0]));
    }

    @Test
    public void errorLimitCountsUtf8BytesAndSupportsMultibyteText() {
        String message = "é".repeat(512);
        ByteBuffer encoded = TransferChunkCodec.encodeError(1, message);
        assertEquals(56 + 1024, encoded.remaining());
        assertArrayEquals(message.getBytes(StandardCharsets.UTF_8),
                TransferChunkCodec.decode(encoded).payload);
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeError(1, message + "a"));
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeError(1, "a".repeat(1025)));
        reject(rawPayload(4, new byte[1025]));
    }

    @Test
    public void emptyNullOrUnpairedSurrogateErrorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TransferChunkCodec.encodeError(1, ""));
        assertThrows(IllegalArgumentException.class, () -> TransferChunkCodec.encodeError(1, null));
        assertThrows(IllegalArgumentException.class, () -> TransferChunkCodec.encodeError(1, "\ud800"));
        reject(rawPayload(4, new byte[0]));
    }

    @Test
    public void malformedUtf8ErrorIsRejectedEvenWithValidDigest() {
        // A DATA encoder supplies a valid digest for these non-UTF-8 bytes.
        ByteBuffer encoded = TransferChunkCodec.encodeData(1, 0,
                new byte[] {(byte) 0xc3, 0x28});
        encoded.put(5, (byte) 4);
        reject(encoded);
    }

    @Test
    public void errorOffsetMustBeZero() {
        ByteBuffer encoded = frame(ERROR);
        encoded.putLong(12, 1);
        reject(encoded);
    }

    @Test
    public void eofRequiresExactly32DigestBytes() {
        for (int length : new int[] {0, 31, 33}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TransferChunkCodec.encodeEof(1, 3, new byte[length]));
        }
        assertThrows(IllegalArgumentException.class,
                () -> TransferChunkCodec.encodeEof(1, 3, null));
    }

    @Test
    public void eofDigestIsMetadataRatherThanHashOfEmptyPayload() {
        byte[] digest = new byte[32];
        digest[31] = 1;
        ByteBuffer encoded = TransferChunkCodec.encodeEof(1, 0, digest);
        digest[31] = 2;
        assertEquals(1, TransferChunkCodec.decode(encoded).sha256[31]);
    }

    @Test
    public void ackDigestMustBeAllZero() {
        ByteBuffer encoded = frame(ACK);
        encoded.put(55, (byte) 1);
        reject(encoded);
    }

    @Test
    public void controlMessagesRejectPayloads() {
        reject(rawPayload(2, abc()));
        reject(rawPayload(3, abc()));
    }

    @Test
    public void wrongMagicIsRejected() {
        ByteBuffer encoded = frame(DATA);
        encoded.putInt(0, 0x48564331);
        reject(encoded);
    }

    @Test
    public void wrongVersionsAreRejected() {
        for (int version : new int[] {0, 2, 255}) {
            ByteBuffer encoded = frame(DATA);
            encoded.put(4, (byte) version);
            reject(encoded);
        }
    }

    @Test
    public void unknownTypesAreRejected() {
        for (int type : new int[] {0, 5, 255}) {
            ByteBuffer encoded = frame(DATA);
            encoded.put(5, (byte) type);
            reject(encoded);
        }
    }

    @Test
    public void eitherFlagsByteMustBeZeroForEveryType() {
        for (String fixture : new String[] {DATA, EOF, ACK, ERROR}) {
            for (int flags : new int[] {1, 256, 32768, 65535}) {
                ByteBuffer encoded = frame(fixture);
                encoded.putShort(6, (short) flags);
                reject(encoded);
            }
        }
    }

    @Test
    public void everyTruncatedHeaderIsRejected() {
        for (int length = 0; length < 56; length++) {
            ByteBuffer encoded = frame(DATA);
            encoded.limit(length);
            reject(encoded);
        }
    }

    @Test
    public void truncatedPayloadsAreRejected() {
        for (String fixture : new String[] {DATA, ERROR}) {
            for (int length = 56; length < 59; length++) {
                ByteBuffer encoded = frame(fixture);
                encoded.limit(length);
                reject(encoded);
            }
        }
    }

    @Test
    public void unsignedOversizedAndMismatchedLengthsAreRejected() {
        for (int length : new int[] {0, 1, 4, 262145, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            ByteBuffer encoded = frame(DATA);
            encoded.putInt(20, length);
            reject(encoded);
        }
    }

    @Test
    public void payloadAndDigestCorruptionAreRejected() {
        for (String fixture : new String[] {DATA, ERROR}) {
            ByteBuffer digest = frame(fixture);
            digest.put(24, (byte) (digest.get(24) ^ 1));
            reject(digest);
            ByteBuffer payload = frame(fixture);
            payload.put(56, (byte) 'x');
            reject(payload);
        }
    }

    @Test
    public void trailingBytesAreRejectedForEveryType() {
        for (String fixture : new String[] {DATA, EOF, ACK, ERROR}) {
            reject(frame(fixture + "00"));
        }
    }

    @Test
    public void nullFrameIsRejected() {
        reject(null);
    }

    @Test
    public void decodeUsesRemainingDirectReadOnlyBytesWithoutChangingCallerState() {
        ByteBuffer encoded = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        encoded.position(2);
        encoded.put(bytes(DATA));
        encoded.limit(encoded.position()).position(2);
        ByteBuffer readOnly = encoded.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        readOnly.mark();
        assertEquals(TransferChunkCodec.Type.DATA, TransferChunkCodec.decode(readOnly).type);
        assertEquals(2, readOnly.position());
        assertEquals(61, readOnly.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, readOnly.order());
        readOnly.reset();
    }

    @Test
    public void failedDecodeLeavesCallerStateUntouched() {
        ByteBuffer encoded = frame(DATA + "00").order(ByteOrder.LITTLE_ENDIAN);
        encoded.mark();
        reject(encoded);
        assertEquals(0, encoded.position());
        assertEquals(60, encoded.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, encoded.order());
        encoded.reset();
    }

    @Test
    public void encodedAndDecodedPayloadsDoNotAliasCallerStorage() {
        byte[] payload = abc();
        ByteBuffer encoded = TransferChunkCodec.encodeData(1, 0, payload);
        payload[0] = 'x';
        assertEquals(DATA, hex(encoded));
        TransferChunkCodec.Message message = TransferChunkCodec.decode(encoded);
        encoded.put(56, (byte) 'y').put(24, (byte) 0);
        assertArrayEquals(abc(), message.payload);
        assertArrayEquals(bytes(SHA_ABC), message.sha256);
    }

    private static void assertMessage(String fixture, TransferChunkCodec.Type type,
            long ordinal, long offset, byte[] payload, String digest) {
        TransferChunkCodec.Message message = TransferChunkCodec.decode(frame(fixture));
        assertEquals(type, message.type);
        assertEquals(ordinal, message.ordinal);
        assertEquals(offset, message.offset);
        assertArrayEquals(payload, message.payload);
        assertArrayEquals(bytes(digest), message.sha256);
    }

    private static void reject(ByteBuffer encoded) {
        assertThrows(IllegalArgumentException.class, () -> TransferChunkCodec.decode(encoded));
    }

    private static ByteBuffer rawPayload(int type, byte[] payload) {
        ByteBuffer result = ByteBuffer.allocate(56 + payload.length);
        try {
            result.putInt(0x48565431).put((byte) 1).put((byte) type).putShort((short) 0)
                    .putInt(1).putLong(0).putInt(payload.length)
                    .put(MessageDigest.getInstance("SHA-256").digest(payload)).put(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        return (ByteBuffer) result.flip();
    }

    private static byte[] abc() {
        return new byte[] {0x61, 0x62, 0x63};
    }

    private static ByteBuffer frame(String hex) {
        return ByteBuffer.wrap(bytes(hex));
    }

    private static byte[] bytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String hex(ByteBuffer buffer) {
        StringBuilder result = new StringBuilder();
        ByteBuffer copy = buffer.duplicate();
        while (copy.hasRemaining()) result.append(String.format("%02x", copy.get() & 0xff));
        return result.toString();
    }
}
