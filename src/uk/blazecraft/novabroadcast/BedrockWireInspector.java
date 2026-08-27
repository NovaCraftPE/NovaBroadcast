package uk.blazecraft.novabroadcast;

import java.util.Optional;

/**
 * Non-mutating diagnostic parser for NetherNet application payloads.
 *
 * It recognizes two conservative shapes:
 *  - a direct Bedrock packet header VarUInt at byte 0; or
 *  - an exact VarUInt byte-length followed by a Bedrock packet header.
 *
 * The second shape is useful for interoperability diagnostics, but is not
 * assumed to be authoritative because Mojang's public NetherNet guide only
 * specifies the outer one-byte data-channel framing.
 */
final class BedrockWireInspector {
    record PacketHeader(int packetId, int senderSubClientId, int targetSubClientId, int encodedBytes) {}

    enum Shape {
        DIRECT_PACKET,
        LENGTH_PREFIXED_PACKET
    }

    record Inspection(Shape shape, PacketHeader header, int packetLength) {}

    static Optional<Inspection> inspect(byte[] payload) {
        if (payload == null || payload.length == 0) return Optional.empty();

        // Prefer the exact-length-prefixed interpretation because it has a
        // strong structural check: declared length must consume the payload.
        VarUInt prefix = readUnsignedVarInt(payload, 0);
        if (prefix != null && prefix.value > 0 && prefix.value == payload.length - prefix.bytes) {
            VarUInt innerHeader = readUnsignedVarInt(payload, prefix.bytes);
            PacketHeader parsed = decodeHeader(innerHeader);
            if (parsed != null && parsed.encodedBytes <= prefix.value) {
                return Optional.of(new Inspection(Shape.LENGTH_PREFIXED_PACKET, parsed, prefix.value));
            }
        }

        VarUInt directHeader = readUnsignedVarInt(payload, 0);
        PacketHeader parsed = decodeHeader(directHeader);
        if (parsed != null) {
            return Optional.of(new Inspection(Shape.DIRECT_PACKET, parsed, payload.length));
        }
        return Optional.empty();
    }

    private static PacketHeader decodeHeader(VarUInt header) {
        if (header == null) return null;
        int packetId = header.value & 0x3ff;
        int sender = (header.value >>> 10) & 0x3;
        int target = (header.value >>> 12) & 0x3;
        if (packetId == 0) return null;
        return new PacketHeader(packetId, sender, target, header.bytes);
    }

    private static VarUInt readUnsignedVarInt(byte[] input, int offset) {
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

    private record VarUInt(int value, int bytes) {}

    private BedrockWireInspector() {}
}
