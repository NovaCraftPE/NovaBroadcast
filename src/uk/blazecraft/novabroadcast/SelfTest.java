package uk.blazecraft.novabroadcast;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class SelfTest {
    static void run() {
        testNetherNetFraming();
        testNetworkSettingsPacket();
        testTransferPacket2168();
        testWireInspection();
        testConnectionTracking();
        testBedrockBatchCodec();
        testRedirectProtocolPackets();
        testNetherNetIdentity();
        System.out.println("[SelfTest] Core tests passed.");
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

    private static void testNetherNetFraming() {
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        List<byte[]> one = NetherNetFraming.frameReliable(hello, 64);
        require(one.size() == 1 && one.get(0)[0] == 0, "single reliable frame header");
        var reassembler = new NetherNetFraming.ReliableReassembler();
        require(Arrays.equals(hello, reassembler.accept(one.get(0)).orElseThrow()), "single reliable reassembly");

        byte[] payload = new byte[25];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        List<byte[]> frames = NetherNetFraming.frameReliable(payload, 10);
        require(frames.size() == 3, "reliable fragmentation count");
        require(Byte.toUnsignedInt(frames.get(0)[0]) == 2 &&
                Byte.toUnsignedInt(frames.get(1)[0]) == 1 && frames.get(2)[0] == 0,
                "reliable countdown headers");
        reassembler = new NetherNetFraming.ReliableReassembler();
        Optional<byte[]> complete = Optional.empty();
        for (byte[] frame : frames) complete = reassembler.accept(frame);
        require(complete.isPresent() && Arrays.equals(payload, complete.get()), "fragmented reliable reassembly");

        reassembler = new NetherNetFraming.ReliableReassembler();
        reassembler.accept(new byte[] {2, 1});
        boolean rejected = false;
        try { reassembler.accept(new byte[] {0, 2}); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "out-of-order reliable frame rejection");

        byte[] move = "move".getBytes(StandardCharsets.UTF_8);
        byte[] unreliable = NetherNetFraming.frameUnreliable(move, 32).orElseThrow();
        require(unreliable[0] == 0, "unreliable frame header");
        require(Arrays.equals(move, NetherNetFraming.stripUnreliable(unreliable)), "unreliable payload");
        require(NetherNetFraming.frameUnreliable(new byte[32], 32).isEmpty(), "oversized unreliable drop");
    }

    private static void testNetworkSettingsPacket() {
        byte[] expected = new byte[] {
                (byte) 0x8f, 0x01,
                0x00, 0x00,
                0x02, 0x00,
                0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] encoded = BedrockNetworkSettingsEncoder.encodeNoCompression();
        require(Arrays.equals(expected, encoded), "NetworkSettings exact bytes");
        require(Arrays.equals(encoded, BedrockRedirectProtocol.networkSettingsNone()),
                "redirect must use verified NetworkSettings encoder");
    }

    private static void testTransferPacket2168() {
        byte[] encoded = BedrockTransferEncoder.encodePacket(2168, "127.0.0.1", 19132, false);
        byte[] expected = new byte[] {
                0x55, 0x09,
                '1','2','7','.','0','.','0','.','1',
                (byte) 0xbc, 0x4a, 0x00, 0x00
        };
        require(Arrays.equals(expected, encoded), "protocol 2168 TransferPacket exact bytes");
    }

    private static void testWireInspection() {
        byte[] packet = BedrockTransferEncoder.encodePacket(2168, "127.0.0.1", 19132, false);
        var direct = BedrockWireInspector.inspect(packet).orElseThrow();
        require(direct.shape() == BedrockWireInspector.Shape.DIRECT_PACKET && direct.header().packetId() == 85,
                "direct packet inspection");

        byte[] prefixed = new byte[packet.length + 1];
        require(packet.length < 128, "test TransferPacket length prefix size");
        prefixed[0] = (byte) packet.length;
        System.arraycopy(packet, 0, prefixed, 1, packet.length);
        var nested = BedrockWireInspector.inspect(prefixed).orElseThrow();
        require(nested.shape() == BedrockWireInspector.Shape.LENGTH_PREFIXED_PACKET && nested.header().packetId() == 85,
                "length-prefixed packet inspection");
    }

    private static void testConnectionTracking() {
        BedrockConnectionTracker tracker = new BedrockConnectionTracker();
        byte[] request = {(byte) 0xc1, 0x01, 0x00, 0x00, 0x08, 0x78};
        var observation = tracker.observe(request).orElseThrow();
        require(observation.packetId() == 193 && Integer.valueOf(2168).equals(observation.requestedProtocol()),
                "RequestNetworkSettings protocol extraction");
        tracker.observe(new byte[] {0x01}).orElseThrow();
        tracker.observe(new byte[] {0x04}).orElseThrow();
        tracker.observe(new byte[] {0x08}).orElseThrow();
        tracker.observe(new byte[] {0x71}).orElseThrow();
        require(tracker.clientInitialized(), "connection tracker client initialized");
        tracker.observe(new byte[] {0x01}).orElseThrow();
        require(tracker.clientInitialized(), "connection tracker monotonic stage");
    }

    private static void testBedrockBatchCodec() {
        byte[] packet = {(byte) 0xc1, 0x01, 0, 0, 8, 0x78};
        byte[] before = BedrockBatchCodec.encodeSingle(packet, true, false);
        require(Byte.toUnsignedInt(before[0]) == 0xfe, "pre-settings game packet marker");
        var decodedBefore = BedrockBatchCodec.decode(before, false).orElseThrow();
        require(Arrays.equals(packet, decodedBefore.packets().get(0)), "pre-settings batch decode");

        byte[] after = BedrockBatchCodec.encodeSingle(new byte[] {0x01}, true, true);
        require(Byte.toUnsignedInt(after[0]) == 0xfe && Byte.toUnsignedInt(after[1]) == 0xff,
                "post-settings NONE method prefix");
        var decodedAfter = BedrockBatchCodec.decode(after, true).orElseThrow();
        require(decodedAfter.compressionPrefix() && decodedAfter.packets().get(0)[0] == 0x01,
                "post-settings batch decode");
    }

    private static void testRedirectProtocolPackets() {
        byte[] login = loginPacket(2168, "connection-request");
        var info = BedrockRedirectProtocol.loginInfo(login);
        require(info != null && info.protocolVersion() == 2168 && info.connectionRequestBytes() == 18,
                "Login fixed-prefix parser");
        require(BedrockRedirectProtocol.loginInfo(new byte[] {0x01}) == null, "malformed Login rejection");
        require(BedrockProtocolVersions.matches("1.26.40", 2168), "1.26.40 protocol mapping");
        require(BedrockProtocolVersions.matches("1.26.43", 2168), "1.26.43 protocol mapping");
        require(BedrockProtocolVersions.matches("1.26.44", 2168), "1.26.44 protocol mapping");
        require(!BedrockProtocolVersions.matches("1.26.45", 2168), "1.26.45 must not map to 2168");
    }

    private static byte[] loginPacket(int protocol, String connectionRequest) {
        byte[] request = connectionRequest.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[1 + 4 + 1 + request.length];
        packet[0] = 0x01;
        packet[1] = (byte) (protocol >>> 24);
        packet[2] = (byte) (protocol >>> 16);
        packet[3] = (byte) (protocol >>> 8);
        packet[4] = (byte) protocol;
        packet[5] = (byte) request.length;
        System.arraycopy(request, 0, packet, 6, request.length);
        return packet;
    }

    private static void testNetherNetIdentity() {
        try {
            Path dir = Files.createTempDirectory("novabroadcast-identity-test");
            Path key = dir.resolve("operator.key");
            NetherNetIdentity first = NetherNetIdentity.loadOrCreate(key, "test.example");
            String fingerprint = first.publicKeyFingerprint();
            NetherNetIdentity second = NetherNetIdentity.loadOrCreate(key, "test.example");
            require(fingerprint.equals(second.publicKeyFingerprint()), "operator identity persistence");

            String offer = "v=0\r\n" +
                    "a=fingerprint:sha-256 AA:BB:CC\r\n" +
                    "a=identity:ZXhhbXBsZQ==\r\n" +
                    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
            NetherNetIdentity.Offer stripped = NetherNetIdentity.stripClientIdentity(offer);
            require(stripped.hasIdentity() && !stripped.cleanSdp().contains("a=identity:"),
                    "client SDP identity stripping");

            String answer = "v=0\r\n" +
                    "o=- 1 2 IN IP4 127.0.0.1\r\n" +
                    "s=-\r\n" +
                    "t=0 0\r\n" +
                    "a=fingerprint:sha-256 11:22:33:44\r\n" +
                    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
            String signed = first.signAnswer(answer);
            require(signed.indexOf("a=identity:") > 0 && signed.indexOf("a=identity:") < signed.indexOf("m=application"),
                    "server SDP identity insertion");

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
