package uk.blazecraft.novabroadcast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Observes client->server Bedrock packets without mutating them.
 *
 * This deliberately tracks only milestones whose packet IDs and payload shape
 * are stable/publicly documented. It does not pretend to implement the full
 * Bedrock login state machine.
 */
final class BedrockConnectionTracker {
    static final int LOGIN = 1;
    static final int CLIENT_TO_SERVER_HANDSHAKE = 4;
    static final int RESOURCE_PACK_CLIENT_RESPONSE = 8;
    static final int SET_LOCAL_PLAYER_AS_INITIALIZED = 113;
    static final int REQUEST_NETWORK_SETTINGS = 193;

    enum Stage {
        NEW,
        NETWORK_SETTINGS_REQUESTED,
        LOGIN_RECEIVED,
        CLIENT_HANDSHAKE_RECEIVED,
        RESOURCE_PACK_RESPONSE_RECEIVED,
        CLIENT_INITIALIZED
    }

    record Observation(Stage stage, int packetId, Integer requestedProtocol) {}

    private Stage stage = Stage.NEW;
    private Integer requestedProtocol;

    synchronized Optional<Observation> observe(byte[] payload) {
        Optional<BedrockWireInspector.Inspection> parsed = BedrockWireInspector.inspect(payload);
        if (parsed.isEmpty()) return Optional.empty();

        BedrockWireInspector.Inspection inspection = parsed.get();
        int packetId = inspection.header().packetId();
        Integer protocol = null;

        if (packetId == REQUEST_NETWORK_SETTINGS) {
            protocol = readRequestNetworkSettingsVersion(payload, inspection);
            if (protocol != null) requestedProtocol = protocol;
            advance(Stage.NETWORK_SETTINGS_REQUESTED);
        } else if (packetId == LOGIN) {
            advance(Stage.LOGIN_RECEIVED);
        } else if (packetId == CLIENT_TO_SERVER_HANDSHAKE) {
            advance(Stage.CLIENT_HANDSHAKE_RECEIVED);
        } else if (packetId == RESOURCE_PACK_CLIENT_RESPONSE) {
            advance(Stage.RESOURCE_PACK_RESPONSE_RECEIVED);
        } else if (packetId == SET_LOCAL_PLAYER_AS_INITIALIZED) {
            advance(Stage.CLIENT_INITIALIZED);
        } else {
            return Optional.empty();
        }

        return Optional.of(new Observation(stage, packetId, protocol));
    }

    synchronized Stage stage() { return stage; }
    synchronized Integer requestedProtocol() { return requestedProtocol; }
    synchronized boolean clientInitialized() { return stage == Stage.CLIENT_INITIALIZED; }

    private void advance(Stage next) {
        if (next.ordinal() > stage.ordinal()) stage = next;
    }

    private static Integer readRequestNetworkSettingsVersion(byte[] payload,
                                                             BedrockWireInspector.Inspection inspection) {
        int packetStart;
        if (inspection.shape() == BedrockWireInspector.Shape.DIRECT_PACKET) {
            packetStart = 0;
        } else {
            VarUInt prefix = readVarUInt(payload, 0);
            if (prefix == null) return null;
            packetStart = prefix.bytes;
        }

        int bodyOffset = packetStart + inspection.header().encodedBytes();
        if (bodyOffset < 0 || bodyOffset + 4 > payload.length) return null;
        return ByteBuffer.wrap(payload, bodyOffset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static VarUInt readVarUInt(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        for (int i = offset; i < bytes.length && i < offset + 5; i++) {
            int b = Byte.toUnsignedInt(bytes[i]);
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new VarUInt(value, i - offset + 1);
            shift += 7;
        }
        return null;
    }

    private record VarUInt(int value, int bytes) {}
}
