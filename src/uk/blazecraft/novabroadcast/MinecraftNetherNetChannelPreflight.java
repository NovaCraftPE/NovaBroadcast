package uk.blazecraft.novabroadcast;

import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Read-only transport preflight that starts the same style of NetherNet server
 * channel required by a live Minecraft broadcast, then immediately shuts it
 * down. No MPSD session or activity handle is written.
 */
final class MinecraftNetherNetChannelPreflight {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration SIGNALING_TIMEOUT = Duration.ofSeconds(20);

    static void run(AppConfig config) throws Exception {
        System.out.println("[NetherNetChannelPreflight] READ-ONLY transport test. MPSD writes remain disabled.");

        MinecraftBedrockAuth.Result auth = new MinecraftBedrockAuth().authenticateDetailed(config.bedrockGameVersion());
        String mcAuthorization = auth.minecraftAuthorizationHeader();
        if (mcAuthorization == null || mcAuthorization.isBlank()) {
            throw new IllegalStateException("NetherNet channel preflight requires a refreshed Minecraft MCToken authorization header.");
        }

        long networkId = positiveRandomLong();
        NetherNetXboxRpcSignaling signaling = new NetherNetXboxRpcSignaling(networkId, mcAuthorization);
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup(1);
        Channel channel = null;

        try {
            System.out.println("[NetherNetChannelPreflight] Authenticating Xbox-RPC signaling...");
            var iceServers = signaling.connect(null)
                    .get(SIGNALING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("[NetherNetChannelPreflight] Signaling authenticated; ICE/TURN entries=" + iceServers.size());

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, workers)
                    .channelFactory(NetherNetChannelFactory.server(new PeerConnectionFactory(), signaling))
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            // The live redirect pipeline is attached in the next milestone.
                            // For this preflight we only prove that the authenticated
                            // NetherNet server transport can be created and bound.
                        }
                    });

            channel = bootstrap.bind(new InetSocketAddress(0)).sync().channel();
            if (!channel.isOpen()) {
                throw new IllegalStateException("NetherNet server channel closed immediately after bind.");
            }

            System.out.println("[NetherNetChannelPreflight] PASS authenticated NetherNet server channel opened successfully.");
            System.out.println("[NetherNetChannelPreflight] Network id and local bind address are intentionally not printed.");
            System.out.println("[NetherNetChannelPreflight] RESULT: transport is ready for the Bedrock redirect pipeline and guarded MPSD publication.");
        } finally {
            if (channel != null) channel.close().syncUninterruptibly();
            signaling.close();
            boss.shutdownGracefully().syncUninterruptibly();
            workers.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static long positiveRandomLong() {
        long value;
        do value = RANDOM.nextLong() & Long.MAX_VALUE;
        while (value == 0);
        return value;
    }

    private MinecraftNetherNetChannelPreflight() {}
}
