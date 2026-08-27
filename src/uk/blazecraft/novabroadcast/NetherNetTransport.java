package uk.blazecraft.novabroadcast;

/**
 * Clean-room NetherNet transport coordinator.
 *
 * The HTTP signaling and data-channel framing layers are implemented here,
 * while ICE/DTLS/SCTP/WebRTC peer creation remains behind PeerBackend.
 */
final class NetherNetTransport implements AutoCloseable {
    static final String RELIABLE_CHANNEL = "ReliableDataChannel";
    static final String UNRELIABLE_CHANNEL = "UnreliableDataChannel";

    private NetherNetSignalingServer signaling;

    void start(AppConfig config) throws Exception {
        if (!config.netherNetEnabled()) return;
        if (signaling != null) return;

        NetherNetSignalingServer.PeerBackend backend = new PendingWebRtcBackend();
        signaling = new NetherNetSignalingServer(
                config.netherNetListenHost(),
                config.netherNetListenPort(),
                config.netherNetMaxSdpBytes(),
                backend);
        signaling.start();

        System.out.println("[NetherNet] HTTP signaling layer initialized.");
        System.out.println("[NetherNet] Expected channels: " + RELIABLE_CHANNEL + ", " + UNRELIABLE_CHANNEL);
        System.out.println("[NetherNet] WebRTC peer backend is not connected yet; /v1/join reports unavailable.");
    }

    @Override
    public void close() {
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
    }

    private static final class PendingWebRtcBackend implements NetherNetSignalingServer.PeerBackend {
        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String answer(String networkId, String offerSdp) {
            throw new UnsupportedOperationException(
                    "ICE/DTLS/SCTP WebRTC peer backend has not been implemented yet");
        }
    }
}
