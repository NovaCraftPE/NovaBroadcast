package uk.blazecraft.novabroadcast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal protocol-2168 packet codec used only to reach a safe redirect point.
 * It does not create a world and does not implement general Bedrock gameplay.
 */
final class BedrockRedirectProtocol {
    static final int SUPPORTED_PROTOCOL = 2168;

    static final int LOGIN = 1;
    static final int PLAY_STATUS = 2;
    static final int RESOURCE_PACKS_INFO = 6;
    static final int RESOURCE_PACK_STACK = 7;
    static final int RESOURCE_PACK_CLIENT_RESPONSE = 8;
    static final int TRANSFER = 85;
    static final int REQUEST_NETWORK_SETTINGS = 193;
    static final int NETWORK_SETTINGS = 143;

    static final int PACK_STATUS_HAVE_ALL_PACKS = 2; // protocol-2168 wire value
    static final int PACK_STATUS_COMPLETED = 3;      // protocol-2168 wire value

    static byte[] networkSettingsNone() {
        ByteArrayOutputStream out = packetHeader(NETWORK_SETTINGS);
        writeShortLE(out, 0);          // compression threshold
        writeShortLE(out, 0xffff);     // Compression::None
        out.write(0);                  // client throttling disabled
        out.write(0);                  // throttle threshold
        writeFloatLE(out, 0.0f);       // throttle scalar
        return out.toByteArray();
    }

    static byte[] playStatusLoginSuccess() {
        ByteArrayOutputStream out = packetHeader(PLAY_STATUS);
        writeIntBE(out, 0); // LoginSuccess
        return out.toByteArray();
    }

    static byte[] emptyResourcePacksInfo() {
        ByteArrayOutputStream out = packetHeader(RESOURCE_PACKS_INFO);
        out.write(0); // forced to accept
        out.write(0); // has addon packs
        out.write(0); // scripting enabled
        out.write(0); // vibrant visuals force disabled
        out.writeBytes(new byte[16]); // zero UUID world template
        BedrockBatchCodec.writeVarUInt(out, 0); // empty template version
        BedrockBatchCodec.writeVarUInt(out, 0); // no resource packs
        return out.toByteArray();
    }

    static byte[] emptyResourcePackStack(String gameVersion) {
        ByteArrayOutputStream out = packetHeader(RESOURCE_PACK_STACK);
        out.write(0); // forced to accept
        BedrockBatchCodec.writeVarUInt(out, 0); // no packs
        writeString(out, gameVersion);
        BedrockBatchCodec.writeVarUInt(out, 0); // no experiments
        out.write(0); // experiments previously toggled
        out.write(0); // has editor packs
        return out.toByteArray();
    }

    static Integer requestNetworkSettingsProtocol(byte[] packet) {
        Header header = header(packet);
        if (header == null || header.packetId != REQUEST_NETWORK_SETTINGS) return null;
        if (header.bytes + 4 > packet.length) return null;
        return ByteBuffer.wrap(packet, header.bytes, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    static Integer resourcePackResponseStatus(byte[] packet) {
        Header header = header(packet);
        if (header == null || header.packetId != RESOURCE_PACK_CLIENT_RESPONSE) return null;
        BedrockBatchCodec.VarUInt status = BedrockBatchCodec.readVarUInt(packet, header.bytes);
        return status == null ? null : status.value();
    }

    static int packetId(byte[] packet) {
        Header header = header(packet);
        return header == null ? -1 : header.packetId;
    }

    static byte[] transfer(int protocolVersion, String host, int port) {
        return BedrockTransferEncoder.encodePacket(protocolVersion, host, port, false);
    }

    private static Header header(byte[] packet) {
        if (packet == null || packet.length == 0) return null;
        BedrockBatchCodec.VarUInt value = BedrockBatchCodec.readVarUInt(packet, 0);
        if (value == null) return null;
        return new Header(value.value() & 0x3ff, value.bytes());
    }

    private static ByteArrayOutputStream packetHeader(int packetId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BedrockBatchCodec.writeVarUInt(out, packetId);
        return out;
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        BedrockBatchCodec.writeVarUInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeIntBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeFloatLE(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToRawIntBits(value);
        out.write(bits & 0xff);
        out.write((bits >>> 8) & 0xff);
        out.write((bits >>> 16) & 0xff);
        out.write((bits >>> 24) & 0xff);
    }

    private record Header(int packetId, int bytes) {}
    private BedrockRedirectProtocol() {}
}
