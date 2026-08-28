package uk.blazecraft.novabroadcast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Minimal clean-room encoder for the server-boundary representation of
 * Bedrock TransferPacket. This deliberately does not implement Bedrock batch,
 * compression, encryption, or connection-state handling.
 */
final class BedrockTransferEncoder {
    static final int TRANSFER_PACKET_ID = 85;
    static final int GATHERINGS_OPTIONAL_PROTOCOL = 2168;

    static byte[] encodePacket(int protocolVersion, String address, int port, boolean reloadWorld) {
        Objects.requireNonNull(address, "address");
        if (address.isBlank()) throw new IllegalArgumentException("Transfer address is blank");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Transfer port must be 0..65535");

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Bedrock packet header VarUInt: packet ID in the low 10 bits, with
        // sender/target sub-client IDs both zero for the primary client.
        writeUnsignedVarInt(out, TRANSFER_PACKET_ID);

        byte[] host = address.getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarInt(out, host.length);
        out.writeBytes(host);

        // uint16 little-endian.
        out.write(port & 0xff);
        out.write((port >>> 8) & 0xff);

        // Reload World.
        out.write(reloadWorld ? 1 : 0);

        // 1.26.40 / protocol 2168 added optional GatheringsConfiguration.
        // NovaBroadcast does not need gatherings context for a plain server
        // transfer, so encode the optional value as absent.
        if (protocolVersion >= GATHERINGS_OPTIONAL_PROTOCOL) {
            out.write(0);
        }

        return out.toByteArray();
    }

    static void writeUnsignedVarInt(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt cannot be negative");
        int remaining = value;
        do {
            int next = remaining & 0x7f;
            remaining >>>= 7;
            if (remaining != 0) next |= 0x80;
            out.write(next);
        } while (remaining != 0);
    }

    private BedrockTransferEncoder() {}
}
