package uk.blazecraft.novabroadcast;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class SelfTest {
    static void run() {
        testUnfragmentedReliable();
        testFragmentedReliable();
        testUnreliable();
        testOutOfOrderRejected();
        testNetworkSettingsPacket();
        testTransferPacket2168();
        testDirectWireInspection();
        testLengthPrefixedWireInspection();
        testConnectionTracking();
        testBedrockBatchCodec();
        testRedirectHandshake2168();
        testNetherNetIdentity();
        System.out.println("[SelfTest] All tests passed.");
    }

    static void runNativeWebRtc() {
        AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);
        try {
            System.out.println("[SelfTest] Native WebRTC headless factory initialized.");
        } finally {
            factory.dispose();
            audioModule.dispose();
        }
        System.out.println("[SelfTest] Native WebRTC smoke test passed.");
    }

    private static void testUnfragmentedReliable() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        List<byte[]> frames = NetherNetFraming.frameReliable(payload, 64);
        require(frames.size() == 1, "expected one reliable frame");
        require(Byte.toUnsignedInt(frames.get(0)[0]) == 0, "unfragmented header must be 0");
        NetherNetFraming.ReliableReassembler r = new NetherNetFraming.ReliableReassembler();
        require(Arrays.equals(payload, r.accept(frames.get(0)).orElseThrow()), "reassembled payload mismatch");
    }

    private static void testFragmentedReliable() {
        byte[] payload = new byte[25];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        List<byte[]> frames = NetherNetFraming.frameReliable(payload, 10);
        require(frames.size() == 3, "expected three fragments");
        require(Byte.toUnsignedInt(frames.get(0)[0]) == 2, "first fragment countdown must be 2");
        require(Byte.toUnsignedInt(frames.get(1)[0]) == 1, "second fragment countdown must be 1");
        require(Byte.toUnsignedInt(frames.get(2)[0]) == 0, "final fragment countdown must be 0");

        NetherNetFraming.ReliableReassembler r = new NetherNetFraming.ReliableReassembler();
        Optional<byte[]> complete = Optional.empty();
        for (byte[] frame : frames) complete = r.accept(frame);
        require(complete.isPresent() && Arrays.equals(payload, complete.get()), "fragmented reassembly mismatch");
    }

    private static void testUnreliable() {
        byte[] payload = "move".getBytes(StandardCharsets.UTF_8);
        byte[] frame = NetherNetFraming.frameUnreliable(payload, 32).orElseThrow();
        require(frame[0] == 0, "unreliable header must be 0");
        require(Arrays.equals(payload, NetherNetFraming.stripUnreliable(frame)), "unreliable payload mismatch");
        require(NetherNetFraming.frameUnreliable(new byte[32], 32).isEmpty(), "oversized unreliable message must drop");
    }

    private static void testOutOfOrderRejected() {
        NetherNetFraming.ReliableReassembler r = new NetherNetFraming.ReliableReassembler();
        r.accept(new byte[] {2, 1});
        boolean rejected = false;
        try {
            r.accept(new byte[] {0, 2});
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "out-of-order fragment must be rejected");
    }

    private static void testNetworkSettingsPacket() {
        byte[] encoded = BedrockNetworkSettingsEncoder.encodeNoCompression();
        byte[] expected = new byte[] {
                (byte) 0x8f, 0x01,
                0x00, 0x00,
                0x02, 0x00,
                0x00,
                0x00,
                0x00, 0x00, 0x00, 0x00
        };
        require(Arrays.equals(expected, encoded), "NetworkSettings bytes mismatch");
        byte[] prefixed = BedrockNetworkSettingsEncoder.matchWireShape(
                encoded, BedrockWireInspector.Shape.LENGTH_PREFIXED_PACKET);
        require(prefixed.length == encoded.length + 1, "expected one-byte NetworkSettings length prefix");
        require(Byte.toUnsignedInt(prefixed[0]) == encoded.length, "NetworkSettings prefix length mismatch");
    }

    private static void testTransferPacket2168() {
        byte[] encoded = BedrockTransferEncoder.encodePacket(2168, "127.0.0.1", 19132, false);
        byte[] expected = new byte[] {
                0x55,
                0x09,
                '1','2','7','.','0','.','0','.','1',
                (byte) 0xbc, 0x4a,
                0x00,
                0x00
        };
        require(Arrays.equals(expected, encoded), "protocol 2168 TransferPacket bytes mismatch");
    }

    private static void testDirectWireInspection() {
        byte[] transfer = BedrockTransferEncoder.encodePacket(2168, "127.0.0.1", 19132, false);
        BedrockWireInspector.Inspection inspection = BedrockWireInspector.inspect(transfer).orElseThrow();
        require(inspection.shape() == BedrockWireInspector.Shape.DIRECT_PACKET, "expected direct packet shape");
        require(inspection.header().packetId() == 85, "expected TransferPacket ID 85");
        require(inspection.header().senderSubClientId() == 0, "expected sender sub-client 0");
        require(inspection.header().targetSubClientId() == 0, "expected target sub-client 0");
    }

    private static void testLengthPrefixedWireInspection() {
        byte[] packet = BedrockTransferEncoder.encodePacket(2168, "127.0.0.1", 19132, false);
        byte[] payload = new byte[packet.length + 1];
        require(packet.length < 128, "test packet unexpectedly requires multi-byte length");
        payload[0] = (byte) packet.length;
        System.arraycopy(packet, 0, payload, 1, packet.length);

        BedrockWireInspector.Inspection inspection = BedrockWireInspector.inspect(payload).orElseThrow();
        require(inspection.shape() == BedrockWireInspector.Shape.LENGTH_PREFIXED_PACKET,
                "expected length-prefixed packet shape");
        require(inspection.packetLength() == packet.length, "length-prefixed packet size mismatch");
        require(inspection.header().packetId() == 85, "expected nested TransferPacket ID 85");
    }

    private static void testConnectionTracking() {
        BedrockConnectionTracker tracker = new BedrockConnectionTracker();
        byte[] requestNetworkSettings2168 = new byte[] {
                (byte) 0xc1, 0x01,
                0x00, 0x00, 0x08, 0x78
        };
        BedrockConnectionTracker.Observation first = tracker.observe(requestNetworkSettings2168).orElseThrow();
        require(first.packetId() == 193, "expected RequestNetworkSettings packet ID");
        require(Integer.valueOf(2168).equals(first.requestedProtocol()), "expected protocol 2168");
        require(tracker.stage() == BedrockConnectionTracker.Stage.NETWORK_SETTINGS_REQUESTED,
                "expected network-settings stage");

        tracker.observe(new byte[] {0x01}).orElseThrow();
        require(tracker.stage() == BedrockConnectionTracker.Stage.LOGIN_RECEIVED, "expected login stage");
        tracker.observe(new byte[] {0x04}).orElseThrow();
        require(tracker.stage() == BedrockConnectionTracker.Stage.CLIENT_HANDSHAKE_RECEIVED,
                "expected client-handshake stage");
        tracker.observe(new byte[] {0x08}).orElseThrow();
        require(tracker.stage() == BedrockConnectionTracker.Stage.RESOURCE_PACK_RESPONSE_RECEIVED,
                "expected resource-pack-response stage");
        tracker.observe(new byte[] {0x71}).orElseThrow();
        require(tracker.clientInitialized(), "expected client initialized stage");
        tracker.observe(new byte[] {0x01}).orElseThrow();
        require(tracker.clientInitialized(), "tracker stage must not move backwards");
    }

    private static void testBedrockBatchCodec() {
        byte[] packet = new byte[] {(byte) 0xc1, 0x01, 0, 0, 8, 0x78};
        byte[] before = BedrockBatchCodec.encodeSingle(packet, true, false);
        require(Byte.toUnsignedInt(before[0]) == 0xfe, "pre-login batch marker missing");
        var decodedBefore = BedrockBatchCodec.decode(before, false).orElseThrow();
        require(decodedBefore.hasGamePacketMarker(), "batch marker not detected");
        require(Arrays.equals(packet, decodedBefore.packets().get(0)), "pre-login packet decode mismatch");

        byte[] after = BedrockBatchCodec.encodeSingle(new byte[] {0x01}, true, true);
        require(Byte.toUnsignedInt(after[0]) == 0xfe && Byte.toUnsignedInt(after[1]) == 0xff,
                "post-NetworkSettings NONE compression prefix missing");
        var decodedAfter = BedrockBatchCodec.decode(after, true).orElseThrow();
        require(decodedAfter.compressionPrefix(), "NONE compression prefix not detected");
        require(decodedAfter.packets().get(0)[0] == 0x01, "post-settings packet decode mismatch");
    }

    private static void testRedirectHandshake2168() {
        try {
            List<byte[]> sent = new ArrayList<>();
            BedrockRedirectSession session = new BedrockRedirectSession(
                    "127.0.0.1", 19132, "1.26.44", true, sent::add);

            byte[] request = new byte[] {(byte) 0xc1, 0x01, 0x00, 0x00, 0x08, 0x78};
            session.accept(BedrockBatchCodec.encodeSingle(request, true, false));
            require(session.stage() == BedrockRedirectSession.Stage.NETWORK_SETTINGS_SENT,
                    "redirect did not send NetworkSettings");
            require(session.compressionNegotiated(), "redirect did not enable post-settings framing");
            var networkSettings = BedrockBatchCodec.decode(sent.get(0), false).orElseThrow();
            require(BedrockRedirectProtocol.packetId(networkSettings.packets().get(0)) == 143,
                    "expected NetworkSettings packet");

            session.accept(BedrockBatchCodec.encodeSingle(new byte[] {0x01}, true, true));
            require(session.stage() == BedrockRedirectSession.Stage.LOGIN_ACCEPTED,
                    "redirect did not accept Login");
            var loginReply = BedrockBatchCodec.decode(sent.get(1), true).orElseThrow();
            require(loginReply.packets().size() == 2, "login reply must contain two packets");
            require(BedrockRedirectProtocol.packetId(loginReply.packets().get(0)) == 2,
                    "login reply missing PlayStatus");
            require(BedrockRedirectProtocol.packetId(loginReply.packets().get(1)) == 6,
                    "login reply missing ResourcePacksInfo");

            session.accept(BedrockBatchCodec.encodeSingle(new byte[] {0x08, 0x02}, true, true));
            require(session.stage() == BedrockRedirectSession.Stage.PACK_STACK_SENT,
                    "redirect did not send ResourcePackStack");
            var stackReply = BedrockBatchCodec.decode(sent.get(2), true).orElseThrow();
            require(BedrockRedirectProtocol.packetId(stackReply.packets().get(0)) == 7,
                    "expected ResourcePackStack packet");

            session.accept(BedrockBatchCodec.encodeSingle(new byte[] {0x08, 0x03}, true, true));
            require(session.stage() == BedrockRedirectSession.Stage.TRANSFER_SENT,
                    "redirect did not send TransferPacket");
            var transferReply = BedrockBatchCodec.decode(sent.get(3), true).orElseThrow();
            require(BedrockRedirectProtocol.packetId(transferReply.packets().get(0)) == 85,
                    "expected final TransferPacket");
        } catch (Exception e) {
            throw new IllegalStateException("Self-test failed: redirect handshake", e);
        }
    }

    private static void testNetherNetIdentity() {
        try {
            Path dir = Files.createTempDirectory("novabroadcast-identity-test");
            Path key = dir.resolve("operator.key");
            NetherNetIdentity first = NetherNetIdentity.loadOrCreate(key, "test.example");
            String fingerprint = first.publicKeyFingerprint();
            NetherNetIdentity second = NetherNetIdentity.loadOrCreate(key, "test.example");
            require(fingerprint.equals(second.publicKeyFingerprint()), "operator identity did not persist");

            String offer = "v=0\r\n" +
                    "a=fingerprint:sha-256 AA:BB:CC\r\n" +
                    "a=identity:ZXhhbXBsZQ==\r\n" +
                    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
            NetherNetIdentity.Offer stripped = NetherNetIdentity.stripClientIdentity(offer);
            require(stripped.hasIdentity(), "client identity was not detected");
            require(!stripped.cleanSdp().contains("a=identity:"), "client identity was not stripped");

            String answer = "v=0\r\n" +
                    "o=- 1 2 IN IP4 127.0.0.1\r\n" +
                    "s=-\r\n" +
                    "t=0 0\r\n" +
                    "a=fingerprint:sha-256 11:22:33:44\r\n" +
                    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
            String signed = first.signAnswer(answer);
            int identityAt = signed.indexOf("a=identity:");
            int mediaAt = signed.indexOf("m=application");
            require(identityAt > 0 && identityAt < mediaAt, "server identity was not inserted before media section");

            Files.deleteIfExists(key);
            Files.deleteIfExists(dir);
        } catch (Exception e) {
            throw new IllegalStateException("Self-test failed: NetherNet identity", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Self-test failed: " + message);
    }

    private SelfTest() {}
}
