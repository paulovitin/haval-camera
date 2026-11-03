package br.com.redesurftank.havalcamera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Encoder and strict decoder for HVT1 version 1 segment transfer messages. */
public final class TransferChunkCodec {
    public static final int MAGIC = 0x48565431; // HVT1
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 56;
    public static final int MAX_DATA_BYTES = 262144;
    public static final int MAX_ERROR_BYTES = 1024;

    private TransferChunkCodec() { }

    public enum Type {
        DATA(1),
        EOF(2),
        ACK(3),
        ERROR(4);

        final int code;

        Type(int code) {
            this.code = code;
        }

        static Type fromCode(int code) {
            for (Type type : values()) if (type.code == code) return type;
            throw new IllegalArgumentException("Unknown HVT1 message type: " + code);
        }
    }

    /** ordinal carries the raw u32 bits, including values above Integer.MAX_VALUE. */
    public static ByteBuffer encodeData(int ordinal, long offset, byte[] payload) {
        return encode(Type.DATA, ordinal, offset, payload, null);
    }

    public static ByteBuffer encodeEof(int ordinal, long totalBytes, byte[] segmentSha256) {
        if (segmentSha256 == null || segmentSha256.length != 32) {
            throw new IllegalArgumentException("EOF requires a 32-byte segment SHA-256");
        }
        return encode(Type.EOF, ordinal, totalBytes, new byte[0], segmentSha256);
    }

    /** nextOffset is the first byte not yet durably persisted by the receiver. */
    public static ByteBuffer encodeAck(int ordinal, long nextOffset) {
        return encode(Type.ACK, ordinal, nextOffset, new byte[0], new byte[32]);
    }

    public static ByteBuffer encodeError(int ordinal, String message) {
        if (message == null || message.isEmpty() || message.length() > MAX_ERROR_BYTES) {
            throw new IllegalArgumentException("ERROR requires 1 to 1024 UTF-8 bytes");
        }
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        if (!message.equals(new String(payload, StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("ERROR contains an unpaired surrogate");
        }
        return encode(Type.ERROR, ordinal, 0, payload, null);
    }

    public static Message decode(ByteBuffer encoded) {
        if (encoded == null) throw new IllegalArgumentException("HVT1 message is null");
        ByteBuffer source = encoded.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (source.remaining() < HEADER_BYTES) throw new IllegalArgumentException("Truncated HVT1 header");
        if (source.getInt() != MAGIC) throw new IllegalArgumentException("Wrong HVT1 magic");
        int version = source.get() & 0xff;
        if (version != VERSION) throw new IllegalArgumentException("Unsupported HVT1 version: " + version);
        Type type = Type.fromCode(source.get() & 0xff);
        if (source.getShort() != 0) throw new IllegalArgumentException("Invalid HVT1 flags");
        long ordinal = Integer.toUnsignedLong(source.getInt());
        long offset = source.getLong();
        long payloadLength = Integer.toUnsignedLong(source.getInt());
        requireFields(type, offset, payloadLength);
        byte[] digest = new byte[32];
        source.get(digest);
        if (source.remaining() != payloadLength) {
            throw new IllegalArgumentException("HVT1 payload length mismatch");
        }
        byte[] payload = new byte[(int) payloadLength];
        source.get(payload);
        if (type == Type.DATA || type == Type.ERROR) {
            if (!MessageDigest.isEqual(digest, sha256(payload))) {
                throw new IllegalArgumentException("HVT1 payload SHA-256 mismatch");
            }
        } else if (type == Type.ACK && !MessageDigest.isEqual(digest, new byte[32])) {
            throw new IllegalArgumentException("ACK digest must be zero");
        }
        if (type == Type.ERROR) {
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload));
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("ERROR contains invalid UTF-8", invalid);
            }
        }
        return new Message(type, ordinal, offset, payload, digest);
    }

    private static ByteBuffer encode(
            Type type, int ordinal, long offset, byte[] payload, byte[] digest) {
        if (payload == null) throw new IllegalArgumentException("HVT1 payload is null");
        requireFields(type, offset, payload.length);
        ByteBuffer result = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .put((byte) VERSION)
                .put((byte) type.code)
                .putShort((short) 0)
                .putInt(ordinal)
                .putLong(offset)
                .putInt(payload.length)
                .put(digest == null ? sha256(payload) : digest)
                .put(payload);
        return (ByteBuffer) result.flip();
    }

    private static void requireFields(Type type, long offset, long payloadLength) {
        if (offset < 0) throw new IllegalArgumentException("HVT1 offset exceeds supported u64 range");
        if (type == Type.DATA) {
            if (payloadLength < 1 || payloadLength > MAX_DATA_BYTES) {
                throw new IllegalArgumentException("DATA requires 1 to 262144 bytes");
            }
            if (offset > Long.MAX_VALUE - payloadLength) {
                throw new IllegalArgumentException("DATA end offset overflows");
            }
        } else if (type == Type.ERROR) {
            if (offset != 0 || payloadLength < 1 || payloadLength > MAX_ERROR_BYTES) {
                throw new IllegalArgumentException("ERROR requires zero offset and 1 to 1024 bytes");
            }
        } else if (payloadLength != 0) {
            throw new IllegalArgumentException("EOF and ACK payloads must be empty");
        }
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static final class Message {
        public final Type type;
        public final long ordinal;
        public final long offset;
        public final byte[] payload;
        public final byte[] sha256;

        private Message(Type type, long ordinal, long offset, byte[] payload, byte[] sha256) {
            this.type = type;
            this.ordinal = ordinal;
            this.offset = offset;
            this.payload = payload;
            this.sha256 = sha256;
        }
    }
}
