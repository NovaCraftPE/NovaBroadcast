package uk.blazecraft.novabroadcast;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Minimal Bedrock game-packet batch framing for the redirect bootstrap.
 * Supports the pre-NetworkSettings batch shape and post-negotiation NONE
 * compression (method 0xff). Zlib/Snappy are deliberately not implemented.
 */
final class BedrockBatchCodec {
    static final int GAME_PACKET_MARKER = 0xfe;
    static final int COMPRESSION_NONE = 0xff;

    record Decoded(List<byte[]> packets, boolean hasGamePacketMarker, boolean compressionPrefix) {}

    static Optional<Decoded> decode(byte[] payload, boolean compressionNegotiated) {
        if (payload == null || payload.length == 0) return Optional.empty();
        int offset = 0;
        boolean marker = Byte.toUnsignedInt(payload[0]) == GAME_PACKET_MARKER;
        if (marker) offset++;

        boolean compressionPrefix = false;
        if (compressionNegotiated) {
            if (offset >= payload.length) return Optional.empty();
            int method = Byte.toUnsignedInt(payload[offset]);
            if (method == COMPRESSION_NONE) {
                compressionPrefix = true;
                offset++;
            } else if (method == 0x00 || method == 0x01) {
                // Known compressed shapes, intentionally unsupported here.
                return Optional.empty();
            }
        }

        List<byte[]> packets = decodeLengthPrefixed(payload, offset);
        if (!packets.isEmpty()) return Optional.of(new Decoded(List.copyOf(packets), marker, compressionPrefix));

        // Compatibility fallback for a transport that exposes one packet
        // directly rather than the RakNet-style batch body.
        if (!marker && !compressionPrefix && offset < payload.length) {
            return Optional.of(new Decoded(List.of(Arrays.copyOfRange(payload, offset, payload.length)), false, false));
        }
        return Optional.empty();
    }

    static byte[] encode(List<byte[]> packets, boolean marker, boolean compressionNegotiated) {
        Objects.requireNonNull(packets, "packets");
        if (packets.isEmpty()) throw new IllegalArgumentException("Bedrock batch must contain at least one packet");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (marker) out.write(GAME_PACKET_MARKER);
        if (compressionNegotiated) out.write(COMPRESSION_NONE);
        for (byte[] packet : packets) {
            Objects.requireNonNull(packet, "packet");
            writeVarUInt(out, packet.length);
            out.writeBytes(packet);
        }
        return out.toByteArray();
    }

    static byte[] encodeSingle(byte[] packet, boolean marker, boolean compressionNegotiated) {
        return encode(List.of(packet), marker, compressionNegotiated);
    }

    private static List<byte[]> decodeLengthPrefixed(byte[] payload, int offset) {
        List<byte[]> packets = new ArrayList<>();
        int cursor = offset;
        while (cursor < payload.length) {
            VarUInt length = readVarUInt(payload, cursor);
            if (length == null || length.value <= 0) return List.of();
            cursor += length.bytes;
            if (length.value > payload.length - cursor) return List.of();
            packets.add(Arrays.copyOfRange(payload, cursor, cursor + length.value));
            cursor += length.value;
        }
        return cursor == payload.length ? packets : List.of();
    }

    static VarUInt readVarUInt(byte[] input, int offset) {
        if (offset < 0 || offset >= input.length) return null;
        int value = 0;
        int shift = 0;
        for (int i = offset; i < input.length && i < offset + 5; i++) {
            int b = Byte.toUnsignedInt(input[i]);
            if (i == offset + 4 && (b & 0xf0) != 0) return null;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new VarUInt(value, i - offset + 1);
            shift += 7;
        }
        return null;
    }

    static void writeVarUInt(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt cannot be negative");
        do {
            int b = value & 0x7f;
            value >>>= 7;
            if (value != 0) b |= 0x80;
            out.write(b);
        } while (value != 0);
    }

    record VarUInt(int value, int bytes) {}
    private BedrockBatchCodec() {}
}
