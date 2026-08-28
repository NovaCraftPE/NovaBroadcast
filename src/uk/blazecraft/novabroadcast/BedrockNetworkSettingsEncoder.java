package uk.blazecraft.novabroadcast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal encoder for the server->client NetworkSettings packet. */
final class BedrockNetworkSettingsEncoder {
    static final int PACKET_ID = 143;
    static final int COMPRESSION_NONE = 2;

    static byte[] encodeNoCompression() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        writeVarUInt(out, PACKET_ID);
        writeShortLE(out, 0); // compression threshold; ignored when algorithm=None
        writeShortLE(out, COMPRESSION_NONE);
        out.write(0); // client throttle disabled
        out.write(0); // throttle threshold
        byte[] scalar = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.0f).array();
        out.writeBytes(scalar);
        return out.toByteArray();
    }

    static byte[] matchWireShape(byte[] packet, BedrockWireInspector.Shape shape) {
        if (shape == BedrockWireInspector.Shape.DIRECT_PACKET) return packet;
        ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length + 5);
        writeVarUInt(out, packet.length);
        out.writeBytes(packet);
        return out.toByteArray();
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException("uint16 out of range");
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeVarUInt(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt cannot be negative");
        do {
            int next = value & 0x7f;
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }

    private BedrockNetworkSettingsEncoder() {}
}
