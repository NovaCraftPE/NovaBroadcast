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
        testTransferPacket2168();
        testDirectWireInspection();
        testLengthPrefixedWireInspection();
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
