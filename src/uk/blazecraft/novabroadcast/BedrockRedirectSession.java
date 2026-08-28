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
    private final int configuredProtocol;
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
        if (targetHost.isBlank()) throw new IllegalArgumentException("target.host cannot be blank");
        if (targetPort < 1 || targetPort > 65535) throw new IllegalArgumentException("target.port is invalid");
        this.targetPort = targetPort;
        this.gameVersion = Objects.requireNonNull(gameVersion).trim();
        this.enabled = enabled;
        this.configuredProtocol = enabled ? BedrockProtocolVersions.requireProtocol(this.gameVersion) : -1;
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
            if (requested != configuredProtocol) {
                System.err.println("[Bedrock] Redirect configured for " + gameVersion +
                        " / protocol " + configuredProtocol + "; client requested " + requested +
                        ". No handshake response will be sent.");
                return;
            }

            sendPacket(BedrockRedirectProtocol.networkSettingsNone(), false);
            compressionNegotiated = true;
            stage = Stage.NETWORK_SETTINGS_SENT;
            System.out.println("[Bedrock] NetworkSettings sent with Compression::None for protocol " + configuredProtocol);
            return;
        }

        if (!enabled || protocolVersion != configuredProtocol) return;

        if (packetId == BedrockRedirectProtocol.LOGIN && stage == Stage.NETWORK_SETTINGS_SENT) {
            BedrockRedirectProtocol.LoginInfo login = BedrockRedirectProtocol.loginInfo(packet);
            if (login == null) {
                System.err.println("[Bedrock] Ignoring malformed Login packet");
                return;
            }
            if (login.protocolVersion() != configuredProtocol) {
                System.err.println("[Bedrock] Login protocol changed from negotiated " + configuredProtocol +
                        " to " + login.protocolVersion() + "; refusing redirect bootstrap");
                return;
            }

            sendPackets(List.of(
                    BedrockRedirectProtocol.playStatusLoginSuccess(),
                    BedrockRedirectProtocol.emptyResourcePacksInfo()), true);
            stage = Stage.LOGIN_ACCEPTED;
            System.out.println("[Bedrock] Login envelope accepted (connectionRequest=" +
                    login.connectionRequestBytes() + " bytes); redirect-only resource-pack negotiation started");
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

    private void sendPacket(byte[] packet, boolean postNegotiation) throws Exception {
        sender.send(BedrockBatchCodec.encodeSingle(packet, marker, postNegotiation));
    }

    private void sendPackets(List<byte[]> packets, boolean postNegotiation) throws Exception {
        sender.send(BedrockBatchCodec.encode(packets, marker, postNegotiation));
    }

    synchronized Stage stage() { return stage; }
    synchronized int protocolVersion() { return protocolVersion; }
    synchronized int configuredProtocol() { return configuredProtocol; }
    synchronized boolean compressionNegotiated() { return compressionNegotiated; }

    private BedrockRedirectSession() { throw new AssertionError(); }
}
