package uk.blazecraft.novabroadcast;

import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Persistent Minecraft-compatible broadcast lifecycle.
 *
 * This mode deliberately requires the existing write guards to be enabled.
 * Startup order is: Bedrock auth -> RTA -> Xbox-RPC/NetherNet -> MPSD session
 * -> activity handle -> accept Bedrock client -> TransferPacket redirect.
 */
final class MinecraftLiveBroadcast implements AutoCloseable {
    private static final String MPSD = "https://sessiondirectory.xboxlive.com";
    private static final String SCID = MinecraftSessionPreflight.SCID;
    private static final String TEMPLATE = MinecraftSessionPreflight.TEMPLATE;
    private static final Duration SIGNALING_TIMEOUT = Duration.ofSeconds(20);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CountDownLatch stopped = new CountDownLatch(1);

    private AppConfig config;
    private MinecraftRtaClient rta;
    private NetherNetXboxRpcSignaling signaling;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel serverChannel;
    private XboxIdentity identity;
    private String sessionName;
    private boolean sessionPublished;
    private boolean activityPublished;

    void run(AppConfig config) throws Exception {
        validateGuards(config);
        this.config = config;

        BedrockTargetProbe.Result target = BedrockTargetProbe.probe(config.targetHost(), config.targetPort(), 3000);
        int expectedProtocol = BedrockProtocolVersions.requireProtocol(config.bedrockGameVersion());
        if (target.protocol() != expectedProtocol) {
            throw new IllegalStateException("Target protocol " + target.protocol() +
                    " does not match bedrock.gameVersion=" + config.bedrockGameVersion() +
                    " (expected " + expectedProtocol + ").");
        }

        MinecraftBedrockAuth.Result auth = new MinecraftBedrockAuth().authenticateDetailed(config.bedrockGameVersion());
        this.identity = auth.identity();
        if (identity.xuid() == null || identity.xuid().isBlank()) {
            throw new IllegalStateException("Live Minecraft broadcast requires an Xbox XUID.");
        }
        if (auth.pmsgId() == null || auth.pmsgId().isBlank()) {
            throw new IllegalStateException("Live Minecraft broadcast requires pmid from the Minecraft session token.");
        }
        if (auth.minecraftAuthorizationHeader() == null || auth.minecraftAuthorizationHeader().isBlank()) {
            throw new IllegalStateException("Live Minecraft broadcast requires Minecraft signaling authorization.");
        }

        this.sessionName = config.sessionName().isBlank() ? UUID.randomUUID().toString() : config.sessionName();
        long networkId = positiveRandomLong();
        String subscriptionId = UUID.randomUUID().toString();

        this.rta = new MinecraftRtaClient();
        System.out.println("[MinecraftLive] Connecting Xbox RTA...");
        String connectionId = rta.connect(identity.authorizationHeader());
        System.out.println("[MinecraftLive] PASS Xbox RTA connected.");

        this.signaling = new NetherNetXboxRpcSignaling(networkId, auth.minecraftAuthorizationHeader());
        System.out.println("[MinecraftLive] Connecting Xbox-RPC NetherNet signaling...");
        var iceServers = signaling.connect(null).get(SIGNALING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        System.out.println("[MinecraftLive] PASS signaling authenticated; ICE/TURN entries=" + iceServers.size());

        this.boss = new NioEventLoopGroup(1);
        this.workers = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, workers)
                .channelFactory(NetherNetChannelFactory.server(new PeerConnectionFactory(), signaling))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                                .addLast("minecraft-nethernet-frame-decoder", new MinecraftNetherNetFrameCodec.Decoder())
                                .addLast("minecraft-nethernet-frame-encoder", new MinecraftNetherNetFrameCodec.Encoder())
                                .addLast("minecraft-bedrock-redirect", new RedirectHandler(config));
                    }
                });

        this.serverChannel = bootstrap.bind(new InetSocketAddress(0)).sync().channel();
        if (!serverChannel.isOpen()) {
            throw new IllegalStateException("Authenticated NetherNet server channel failed to open.");
        }
        System.out.println("[MinecraftLive] PASS authenticated NetherNet server channel is open.");

        String worldName = config.targetName().isBlank() ? target.motd() : config.targetName();
        String sessionDocument = MinecraftSessionPreflight.buildDocument(
                identity.xuid(), connectionId, subscriptionId,
                networkId, auth.pmsgId(), worldName, worldName,
                Math.max(0, target.players()), Math.max(1, target.maxPlayers()),
                target.protocol(), target.version());

        Http.Response sessionResponse = Http.put(sessionUri(), sessionDocument, mpsdHeaders(true));
        if (!sessionResponse.ok()) {
            throw new IllegalStateException("MinecraftLobby session publication failed: HTTP " +
                    sessionResponse.status() + " " + sanitized(sessionResponse.body()));
        }
        this.sessionPublished = true;
        System.out.println("[MinecraftLive] PASS MinecraftLobby session published.");

        String activityBody = "{" +
                "\"version\":1," +
                "\"type\":\"activity\"," +
                "\"sessionRef\":{" +
                    "\"scid\":" + Json.quote(SCID) + "," +
                    "\"templateName\":" + Json.quote(TEMPLATE) + "," +
                    "\"name\":" + Json.quote(sessionName) +
                "}" +
            "}";
        Http.Response activityResponse = Http.post(MPSD + "/handles", activityBody, mpsdHeaders(true));
        if (!activityResponse.ok()) {
            throw new IllegalStateException("Xbox activity handle publication failed: HTTP " +
                    activityResponse.status() + " " + sanitized(activityResponse.body()));
        }
        this.activityPublished = true;
        System.out.println("[MinecraftLive] PASS Xbox activity handle published.");
        System.out.println("[MinecraftLive] LIVE: friends can now attempt to join this account.");
        System.out.println("[MinecraftLive] Redirect target: " + config.targetHost() + ":" + config.targetPort());
        System.out.println("[MinecraftLive] Press Ctrl+C to stop and clean up the published session.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { close(); } catch (Exception ignored) {}
        }, "NovaBroadcast-MinecraftLive-shutdown"));

        serverChannel.closeFuture().sync();
    }

    private final class RedirectHandler extends ChannelInboundHandlerAdapter {
        private final AppConfig cfg;
        private BedrockRedirectSession redirect;

        private RedirectHandler(AppConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            this.redirect = new BedrockRedirectSession(
                    cfg.targetHost(), cfg.targetPort(), cfg.bedrockGameVersion(), true,
                    payload -> ctx.writeAndFlush(Unpooled.wrappedBuffer(payload)));
            System.out.println("[MinecraftLive] Bedrock client connected through NetherNet.");
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                ctx.fireChannelRead(msg);
                return;
            }
            try {
                byte[] payload = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), payload);
                if (redirect != null) redirect.accept(payload);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[MinecraftLive] Client transport error: " + cause.getMessage());
            ctx.close();
        }
    }

    private void validateGuards(AppConfig config) {
        if (!config.sessionEnabled()) {
            throw new IllegalStateException("--minecraft-live requires session.enabled=true.");
        }
        if (!config.sessionWriteEnabled()) {
            throw new IllegalStateException("--minecraft-live requires session.writeEnabled=true.");
        }
        if (!config.sessionSetActivity()) {
            throw new IllegalStateException("--minecraft-live requires session.setActivity=true.");
        }
        if (!config.bedrockRedirectEnabled()) {
            throw new IllegalStateException("--minecraft-live requires bedrock.redirectEnabled=true.");
        }
        if (config.targetHost().isBlank()) {
            throw new IllegalStateException("target.host cannot be blank.");
        }
    }

    private Map<String,String> mpsdHeaders(boolean contentType) {
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("Authorization", identity.authorizationHeader());
        headers.put("Accept", "application/json");
        headers.put("x-xbl-contract-version", "107");
        if (contentType) headers.put("Content-Type", "application/json");
        return headers;
    }

    private String sessionUri() {
        return MPSD + "/serviceconfigs/" + encode(SCID) +
                "/sessiontemplates/" + encode(TEMPLATE) +
                "/sessions/" + encode(sessionName);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long positiveRandomLong() {
        long value;
        do value = RANDOM.nextLong() & Long.MAX_VALUE;
        while (value == 0);
        return value;
    }

    private static String sanitized(String body) {
        if (body == null) return "";
        String cleaned = body.replaceAll("(?i)XBL3\\.0[^\\\"\\s]*", "[redacted]")
                .replaceAll("(?i)Bearer\\s+[^\\\"\\s]+", "Bearer [redacted]")
                .replaceAll("(?i)MCToken\\s+[^\\\"\\s]+", "MCToken [redacted]")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
        return cleaned.length() > 800 ? cleaned.substring(0, 800) + "…" : cleaned;
    }

    @Override
    public synchronized void close() {
        if (stopped.getCount() == 0) return;
        try {
            if (sessionPublished && identity != null && sessionName != null) {
                try {
                    Http.Response leave = Http.delete(sessionUri() + "/members/me", mpsdHeaders(false));
                    if (leave.ok() || leave.status() == 404) {
                        System.out.println("[MinecraftLive] Published MPSD member removed.");
                    } else {
                        System.err.println("[MinecraftLive] MPSD cleanup returned HTTP " + leave.status());
                    }
                } catch (Exception e) {
                    System.err.println("[MinecraftLive] MPSD cleanup failed: " + e.getMessage());
                }
            }
        } finally {
            sessionPublished = false;
            activityPublished = false;
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
                serverChannel = null;
            }
            if (signaling != null) {
                signaling.close();
                signaling = null;
            }
            if (rta != null) {
                rta.close();
                rta = null;
            }
            if (boss != null) {
                boss.shutdownGracefully().syncUninterruptibly();
                boss = null;
            }
            if (workers != null) {
                workers.shutdownGracefully().syncUninterruptibly();
                workers = null;
            }
            stopped.countDown();
        }
    }
}
