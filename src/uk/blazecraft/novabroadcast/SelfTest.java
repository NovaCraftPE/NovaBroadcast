package uk.blazecraft.novabroadcast;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class SelfTest {
    static void run() {
        testUnfragmentedReliable();
        testFragmentedReliable();
        testUnreliable();
        testOutOfOrderRejected();
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Self-test failed: " + message);
    }

    private SelfTest() {}
}
