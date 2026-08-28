package uk.blazecraft.novabroadcast;

import dev.kastle.webrtc.PeerConnectionFactory;

/**
 * Exercises the exact WebRTC JNI entry point used by netty-transport-nethernet.
 * This intentionally does not use the separate Onvoid WebRTC API.
 */
public final class KastleWebRtcSmokeTest {
    public static void main(String[] args) {
        run();
    }

    static void run() {
        new PeerConnectionFactory();
        System.out.println("[KastleWebRtcSmokeTest] PeerConnectionFactory initialized with Kastle WebRTC JNI.");
        System.out.println("[KastleWebRtcSmokeTest] PASS native runtime matches NetherNet.");
    }

    private KastleWebRtcSmokeTest() {}
}
