package uk.blazecraft.novabroadcast;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Builds a MinecraftLobby-compatible session document without writing it.
 * This validates the remaining dynamic prerequisites: Bedrock auth, pmid,
 * Xbox RTA connection id, target protocol and the Minecraft session shape.
 */
final class MinecraftSessionPreflight {
    static final String SCID = "4fc10100-5f7a-4470-899b-280835760c07";
    static final String TEMPLATE = "MinecraftLobby";
    static final int TITLE_ID = 896928775;
    private static final SecureRandom RANDOM = new SecureRandom();

    static void run(AppConfig config) throws Exception {
        System.out.println("NovaBroadcast " + NovaBroadcast.VERSION);
        System.out.println("[MinecraftSessionPreflight] READ-ONLY. No MPSD session or activity handle will be written.");

        MinecraftBedrockAuth.Result auth = new MinecraftBedrockAuth().authenticateDetailed(config.bedrockGameVersion());
        XboxIdentity identity = auth.identity();
        if (identity.xuid() == null || identity.xuid().isBlank()) {
            throw new IllegalStateException("Minecraft session preflight requires an Xbox XUID.");
        }
        if (auth.pmsgId() == null || auth.pmsgId().isBlank()) {
            throw new IllegalStateException("Minecraft session preflight requires pmid from the Minecraft session token.");
        }
        System.out.println("[MinecraftSessionPreflight] Minecraft session pmid is available (not printed).");

        BedrockTargetProbe.Result target = BedrockTargetProbe.probe(config.targetHost(), config.targetPort(), 3000);
        int configuredProtocol = BedrockProtocolVersions.requireProtocol(config.bedrockGameVersion());
        if (target.protocol() != configuredProtocol) {
            throw new IllegalStateException("Target protocol " + target.protocol() +
                    " does not match bedrock.gameVersion=" + config.bedrockGameVersion() +
                    " (expected " + configuredProtocol + ").");
        }
        System.out.println("[MinecraftSessionPreflight] Target: " + target.motd() + " / " + target.version() +
                " / protocol " + target.protocol());

        String connectionId;
        try (MinecraftRtaClient rta = new MinecraftRtaClient()) {
            System.out.println("[MinecraftSessionPreflight] Connecting to Xbox RTA...");
            connectionId = rta.connect(identity.authorizationHeader());
            System.out.println("[MinecraftSessionPreflight] PASS Xbox RTA connection id received (not printed).");
        }

        long raw = RANDOM.nextLong();
        long netherNetId = raw == Long.MIN_VALUE ? 0 : Math.abs(raw);
        String sessionName = UUID.randomUUID().toString();
        String subscriptionId = UUID.randomUUID().toString();
        String worldName = config.targetName().isBlank() ? target.motd() : config.targetName();
        String hostName = worldName;
        int players = Math.max(0, target.players());
        int maxPlayers = Math.max(1, target.maxPlayers());

        String document = buildDocument(
                identity.xuid(), connectionId, subscriptionId,
                netherNetId, auth.pmsgId(), hostName, worldName,
                players, maxPlayers, target.protocol(), target.version());

        Path out = Path.of("data/minecraft-session-preflight.json");
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, document, StandardCharsets.UTF_8);

        Path ref = Path.of("data/minecraft-session-ref.txt");
        Files.writeString(ref,
                "scid=" + SCID + System.lineSeparator() +
                "template=" + TEMPLATE + System.lineSeparator() +
                "name=" + sessionName + System.lineSeparator() +
                "titleId=" + TITLE_ID + System.lineSeparator() +
                "netherNetId=" + netherNetId + System.lineSeparator(),
                StandardCharsets.UTF_8);

        System.out.println("[MinecraftSessionPreflight] PASS MinecraftLobby session document prepared.");
        System.out.println("[MinecraftSessionPreflight] Saved: " + out.toAbsolutePath());
        System.out.println("[MinecraftSessionPreflight] No authentication tokens, pmid or RTA connection id were logged.");
        System.out.println("[MinecraftSessionPreflight] RESULT: ready for Xbox-RPC NetherNet signaling integration; MPSD writes remain disabled.");
    }

    static String buildDocument(String xuid, String connectionId, String subscriptionId,
                                long netherNetId, String pmsgId, String hostName, String worldName,
                                int players, int maxPlayers, int protocol, String version) {
        return "{" +
                "\"properties\":{" +
                    "\"system\":{" +
                        "\"joinRestriction\":\"followed\"," +
                        "\"readRestriction\":\"followed\"," +
                        "\"closed\":false" +
                    "}," +
                    "\"custom\":{" +
                        "\"BroadcastSetting\":3," +
                        "\"CrossPlayDisabled\":false," +
                        "\"Joinability\":\"joinable_by_friends\"," +
                        "\"LanGame\":false," +
                        "\"MaxMemberCount\":" + maxPlayers + "," +
                        "\"MemberCount\":" + players + "," +
                        "\"OnlineCrossPlatformGame\":true," +
                        "\"SupportedConnections\":[{" +
                            "\"ConnectionType\":7," +
                            "\"HostIpAddress\":\"\"," +
                            "\"HostPort\":0," +
                            "\"NetherNetId\":" + netherNetId + "," +
                            "\"PmsgId\":" + Json.quote(pmsgId) +
                        "}]," +
                        "\"TitleId\":0," +
                        "\"TransportLayer\":2," +
                        "\"levelId\":\"level\"," +
                        "\"hostName\":" + Json.quote(hostName) + "," +
                        "\"ownerId\":" + Json.quote(xuid) + "," +
                        "\"rakNetGUID\":\"\"," +
                        "\"worldName\":" + Json.quote(worldName) + "," +
                        "\"worldType\":\"Survival\"," +
                        "\"protocol\":" + protocol + "," +
                        "\"version\":" + Json.quote(version) + "," +
                        "\"isEditorWorld\":false," +
                        "\"isHardcore\":false," +
                        "\"nonces\":{}" +
                    "}" +
                "}," +
                "\"members\":{" +
                    "\"me\":{" +
                        "\"constants\":{" +
                            "\"system\":{" +
                                "\"xuid\":" + Json.quote(xuid) + "," +
                                "\"initialize\":true" +
                            "}" +
                        "}," +
                        "\"properties\":{" +
                            "\"system\":{" +
                                "\"active\":true," +
                                "\"connection\":" + Json.quote(connectionId) + "," +
                                "\"subscription\":{" +
                                    "\"id\":" + Json.quote(subscriptionId) + "," +
                                    "\"changeTypes\":[\"everything\"]" +
                                "}" +
                            "}" +
                        "}" +
                    "}" +
                "}" +
            "}";
    }

    private MinecraftSessionPreflight() {}
}
