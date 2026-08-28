package uk.blazecraft.novabroadcast;

import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Connects to Minecraft's Xbox-RPC NetherNet signaling service and verifies
 * that the refreshed Minecraft session token can register a signaling peer and
 * obtain ICE/TURN configuration. No MPSD session or activity handle is written.
 */
final class XboxRpcSignalingPreflight {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final SecureRandom RANDOM = new SecureRandom();

    static void run(AppConfig config) throws Exception {
        System.out.println("[XboxRpcPreflight] READ-ONLY signaling test. MPSD writes remain disabled.");

        MinecraftBedrockAuth.Result auth = new MinecraftBedrockAuth().authenticateDetailed(config.bedrockGameVersion());
        String mcAuthorization = auth.minecraftAuthorizationHeader();
        if (mcAuthorization == null || mcAuthorization.isBlank()) {
            throw new IllegalStateException("Xbox RPC preflight requires a refreshed Minecraft MCToken authorization header.");
        }

        long networkId = positiveRandomLong();
        NetherNetXboxRpcSignaling signaling = new NetherNetXboxRpcSignaling(networkId, mcAuthorization);
        try {
            System.out.println("[XboxRpcPreflight] Connecting to Minecraft Xbox-RPC signaling...");
            var iceServers = signaling.connect(null)
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            System.out.println("[XboxRpcPreflight] PASS signaling WebSocket authenticated and TURN request completed.");
            System.out.println("[XboxRpcPreflight] Local NetherNet network id allocated (not printed).");
            System.out.println("[XboxRpcPreflight] ICE/TURN server entries received: " + iceServers.size());
            System.out.println("[XboxRpcPreflight] RESULT: Xbox-RPC signaling is ready for the NetherNet server channel.");
        } finally {
            signaling.close();
        }
    }

    private static long positiveRandomLong() {
        long value;
        do value = RANDOM.nextLong() & Long.MAX_VALUE;
        while (value == 0);
        return value;
    }

    private XboxRpcSignalingPreflight() {}
}
