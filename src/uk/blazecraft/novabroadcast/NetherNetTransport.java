package uk.blazecraft.novabroadcast;

import java.util.concurrent.CountDownLatch;

/** Clean-room NetherNet transport coordinator. */
final class NetherNetTransport implements AutoCloseable {
    static final String RELIABLE_CHANNEL = "ReliableDataChannel";
    static final String UNRELIABLE_CHANNEL = "UnreliableDataChannel";

    private final CountDownLatch stopped = new CountDownLatch(1);
    private NetherNetSignalingServer signaling;
    private WebRtcPeerBackend backend;

    void start(AppConfig config) throws Exception {
        if (!config.netherNetEnabled()) return;
        if (signaling != null) return;

        backend = new WebRtcPeerBackend(config);
        signaling = new NetherNetSignalingServer(
                config.netherNetListenHost(),
                config.netherNetListenPort(),
                config.netherNetMaxSdpBytes(),
                backend);
        signaling.start();

        System.out.println("[NetherNet] Native WebRTC peer backend initialized.");
        System.out.println("[NetherNet] Expected channels: " + RELIABLE_CHANNEL + ", " + UNRELIABLE_CHANNEL);
        System.out.println("[NetherNet] ICE UDP range: " + config.netherNetIceMinPort() + "-" + config.netherNetIceMaxPort());
        if (!config.netherNetStunUrl().isBlank()) {
            System.out.println("[NetherNet] STUN: " + config.netherNetStunUrl());
        }
    }

    void await() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
        if (backend != null) {
            backend.close();
            backend = null;
        }
        stopped.countDown();
    }
}
