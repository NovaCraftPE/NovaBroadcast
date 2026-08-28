package uk.blazecraft.novabroadcast;

import java.util.*;

final class BedrockRedirectSelfTest {
    static void run() {
        try {
            testDisabledIsDiagnosticOnly();
            testProtocolMismatchIsRejected();
            test2168RedirectFlow();
        } catch (Exception e) {
            throw new IllegalStateException("Self-test failed: Bedrock redirect", e);
        }
    }

    private static void testDisabledIsDiagnosticOnly() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        BedrockRedirectSession session = new BedrockRedirectSession(
                "127.0.0.1", 19132, "future-version-is-okay-while-disabled", false, sent::add);
        session.accept(BedrockBatchCodec.encodeSingle(requestNetworkSettings(2168), true, false));
        require(sent.isEmpty(), "disabled redirect emitted network traffic");
        require(session.stage() == BedrockRedirectSession.Stage.NEW, "disabled redirect changed stage");
    }

    private static void testProtocolMismatchIsRejected() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        BedrockRedirectSession session = new BedrockRedirectSession(
                "127.0.0.1", 19132, "1.26.44", true, sent::add);
        session.accept(BedrockBatchCodec.encodeSingle(requestNetworkSettings(2169), true, false));
        require(sent.isEmpty(), "protocol mismatch emitted NetworkSettings");
        require(session.stage() == BedrockRedirectSession.Stage.NEW, "protocol mismatch changed stage");
        require(session.configuredProtocol() == 2168, "1.26.44 must map to protocol 2168");
    }

    private static void test2168RedirectFlow() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        BedrockRedirectSession session = new BedrockRedirectSession(
                "127.0.0.1", 19132, "1.26.44", true, sent::add);

        session.accept(BedrockBatchCodec.encodeSingle(requestNetworkSettings(2168), true, false));
        require(session.stage() == BedrockRedirectSession.Stage.NETWORK_SETTINGS_SENT,
                "redirect did not send NetworkSettings");
        require(session.compressionNegotiated(), "redirect did not enter post-settings framing");
        var networkSettingsBatch = BedrockBatchCodec.decode(sent.get(0), false).orElseThrow();
        require(networkSettingsBatch.packets().size() == 1, "NetworkSettings batch packet count mismatch");
        require(Arrays.equals(BedrockNetworkSettingsEncoder.encodeNoCompression(),
                        networkSettingsBatch.packets().get(0)),
                "redirect NetworkSettings differs from verified encoder");

        session.accept(BedrockBatchCodec.encodeSingle(new byte[] {0x01}, true, true));
        require(session.stage() == BedrockRedirectSession.Stage.LOGIN_ACCEPTED,
                "redirect did not advance on Login");
        var loginReply = BedrockBatchCodec.decode(sent.get(1), true).orElseThrow();
        require(loginReply.packets().size() == 2, "login reply must contain PlayStatus and ResourcePacksInfo");
        require(BedrockRedirectProtocol.packetId(loginReply.packets().get(0)) == 2,
                "login reply missing PlayStatus");
        require(BedrockRedirectProtocol.packetId(loginReply.packets().get(1)) == 6,
                "login reply missing ResourcePacksInfo");

        session.accept(BedrockBatchCodec.encodeSingle(resourcePackResponse(2, "downloadingfinished"), true, true));
        require(session.stage() == BedrockRedirectSession.Stage.PACK_STACK_SENT,
                "redirect did not send ResourcePackStack");
        var stackReply = BedrockBatchCodec.decode(sent.get(2), true).orElseThrow();
        require(BedrockRedirectProtocol.packetId(stackReply.packets().get(0)) == 7,
                "expected ResourcePackStack");

        session.accept(BedrockBatchCodec.encodeSingle(resourcePackResponse(3, "resourcepackstackfinished"), true, true));
        require(session.stage() == BedrockRedirectSession.Stage.TRANSFER_SENT,
                "redirect did not send TransferPacket");
        var transferReply = BedrockBatchCodec.decode(sent.get(3), true).orElseThrow();
        require(BedrockRedirectProtocol.packetId(transferReply.packets().get(0)) == 85,
                "expected TransferPacket");
    }

    private static byte[] requestNetworkSettings(int protocol) {
        return new byte[] {
                (byte) 0xc1, 0x01,
                (byte) (protocol >>> 24), (byte) (protocol >>> 16),
                (byte) (protocol >>> 8), (byte) protocol
        };
    }

    private static byte[] resourcePackResponse(int status, String type) {
        byte[] text = type.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] packet = new byte[2 + text.length];
        packet[0] = 0x08;
        packet[1] = (byte) status;
        // The v2168 packet also carries a status-string field. The redirect
        // only needs the status value, but tests include the real field shape.
        byte[] full = new byte[packet.length + 1];
        full[0] = packet[0];
        full[1] = packet[1];
        full[2] = (byte) text.length;
        System.arraycopy(text, 0, full, 3, text.length);
        return full;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private BedrockRedirectSelfTest() {}
}
