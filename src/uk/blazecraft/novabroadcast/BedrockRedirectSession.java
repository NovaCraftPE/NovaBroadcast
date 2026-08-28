package uk.blazecraft.novabroadcast;

import java.util.*;

/**
 * Drives the smallest Bedrock login flow needed to redirect a client.
 *
 * Flow for protocol 2168:
 * RequestNetworkSettings -> NetworkSettings(None)
 * Login -> PlayStatus(LoginSuccess) + empty ResourcePacksInfo
 * ResourcePackClientResponse(HaveAllPacks) -> empty ResourcePackStack
 * ResourcePackClientResponse(Completed) -> TransferPacket
 */
final class BedrockRedirectSession {
    interface Sender { void send(byte[] applicationPayload) throws Exception; }

    enum Stage {
        NEW,
        NETWORK_SETTINGS_SENT,
        LOGIN_ACCEPTED,
        PACK_STACK_SENT,
        TRANSFER_SENT
    }

    private final String targetHost;
    private final int targetPort;
    private final String gameVersion;
    private final boolean enabled;
    private final Sender sender;
    private Stage stage = Stage.NEW;
    private boolean marker;
    private boolean wireShapeKnown;
    private boolean compressionNegotiated;
    private int protocolVersion = -1;

    BedrockRedirectSession(String targetHost, int targetPort, String gameVersion,
                           boolean enabled, Sender sender) {
        this.targetHost = Objects.requireNonNull(targetHost);
        this.targetPort = targetPort;
        this.gameVersion = Objects.requireNonNull(gameVersion);
        this.enabled = enabled;
        this.sender = Objects.requireNonNull(sender);
    }

    synchronized void accept(byte[] applicationPayload) throws Exception {
        Optional<BedrockBatchCodec.Decoded> decoded = BedrockBatchCodec.decode(applicationPayload, compressionNegotiated);
        if (decoded.isEmpty()) return;

        BedrockBatchCodec.Decoded batch = decoded.get();
        if (!wireShapeKnown) {
            marker = batch.hasGamePacketMarker();
            wireShapeKnown = true;
        }

        for (byte[] packet : batch.packets()) handlePacket(packet);
    }

    private void handlePacket(byte[] packet) throws Exception {
        int packetId = BedrockRedirectProtocol.packetId(packet);
        if (packetId == BedrockRedirectProtocol.REQUEST_NETWORK_SETTINGS && stage == Stage.NEW) {
            Integer requested = BedrockRedirectProtocol.requestNetworkSettingsProtocol(packet);
            if (requested == null) return;
            protocolVersion = requested;
            System.out.println("[Bedrock] Client requested protocol " + requested);
            if (!enabled) return;
            if (requested != BedrockRedirectProtocol.SUPPORTED_PROTOCOL) {
                System.err.println("[Bedrock] Redirect bootstrap supports protocol " +
                        BedrockRedirectProtocol.SUPPORTED_PROTOCOL + " only; client requested " + requested);
                return;
            }

            // NetworkSettings itself is sent using the pre-negotiation shape.
            sendPacket(BedrockRedirectProtocol.networkSettingsNone(), false);
            compressionNegotiated = true;
            stage = Stage.NETWORK_SETTINGS_SENT;
            System.out.println("[Bedrock] NetworkSettings sent with Compression::None");
            return;
        }

        if (!enabled || protocolVersion != BedrockRedirectProtocol.SUPPORTED_PROTOCOL) return;

        if (packetId == BedrockRedirectProtocol.LOGIN && stage == Stage.NETWORK_SETTINGS_SENT) {
            sendPackets(List.of(
                    BedrockRedirectProtocol.playStatusLoginSuccess(),
                    BedrockRedirectProtocol.emptyResourcePacksInfo()), true);
            stage = Stage.LOGIN_ACCEPTED;
            System.out.println("[Bedrock] Login accepted; empty resource-pack negotiation started");
            return;
        }

        if (packetId == BedrockRedirectProtocol.RESOURCE_PACK_CLIENT_RESPONSE) {
            Integer status = BedrockRedirectProtocol.resourcePackResponseStatus(packet);
            if (status == null) return;

            if (status == BedrockRedirectProtocol.PACK_STATUS_HAVE_ALL_PACKS && stage == Stage.LOGIN_ACCEPTED) {
                sendPacket(BedrockRedirectProtocol.emptyResourcePackStack(gameVersion), true);
                stage = Stage.PACK_STACK_SENT;
                System.out.println("[Bedrock] Client has all packs; empty resource-pack stack sent");
                return;
            }

            if (status == BedrockRedirectProtocol.PACK_STATUS_COMPLETED && stage == Stage.PACK_STACK_SENT) {
                sendPacket(BedrockRedirectProtocol.transfer(protocolVersion, targetHost, targetPort), true);
                stage = Stage.TRANSFER_SENT;
                System.out.println("[Bedrock] Transfer sent to " + targetHost + ":" + targetPort);
            }
        }
    }

    private void sendPacket(byte[] packet, boolean compressedPhase) throws Exception {
        sender.send(BedrockBatchCodec.encodeSingle(packet, marker, compressedPhase));
    }

    private void sendPackets(List<byte[]> packets, boolean compressedPhase) throws Exception {
        sender.send(BedrockBatchCodec.encode(packets, marker, compressedPhase));
    }

    synchronized Stage stage() { return stage; }
    synchronized int protocolVersion() { return protocolVersion; }
    synchronized boolean compressionNegotiated() { return compressionNegotiated; }

    private BedrockRedirectSession() { throw new AssertionError(); }
}
