package uk.blazecraft.novabroadcast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only probe for the Minecraft Windows MPSD and session prerequisites. */
final class MinecraftMpsdPreflight {
    static final String SERVICE_CONFIG_ID = "4fc10100-5f7a-4470-899b-280835760c07";
    static final String TEMPLATE_NAME = "MinecraftLobby";
    static final String TITLE_ID = "896928775";

    private MinecraftMpsdPreflight() {}

    static void run(AppConfig config) throws Exception {
        System.out.println("NovaBroadcast 0.7-xbox-rpc-preflight");
        System.out.println("[MinecraftPreflight] READ-ONLY test. No session or activity handle will be created.");

        BedrockTargetProbe.Result target = BedrockTargetProbe.probe(config.targetHost(), config.targetPort(), 3000);
        int expected = BedrockProtocolVersions.requireProtocol(config.bedrockGameVersion());
        System.out.println("[MinecraftPreflight] Target: " + target.motd() + " / " + target.version() +
                " / protocol " + target.protocol());
        if (target.protocol() != expected) {
            throw new IllegalStateException("Target protocol " + target.protocol() +
                    " does not match configured Bedrock version " + config.bedrockGameVersion() +
                    " (expected " + expected + ").");
        }

        XboxIdentity identity = new MinecraftBedrockAuth().authenticate(config.bedrockGameVersion());

        String url = "https://sessiondirectory.xboxlive.com/serviceconfigs/" + encode(SERVICE_CONFIG_ID) +
                "/sessiontemplates/" + encode(TEMPLATE_NAME);
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("Authorization", identity.authorizationHeader());
        headers.put("Accept", "application/json");
        headers.put("x-xbl-contract-version", "107");

        System.out.println("[MinecraftPreflight] Checking Minecraft MPSD: scid=" + SERVICE_CONFIG_ID +
                " template=" + TEMPLATE_NAME + " titleId=" + TITLE_ID);
        Http.Response response = Http.get(url, headers);
        if (!response.ok()) {
            String body = sanitize(response.body());
            throw new IllegalStateException("Minecraft MPSD read-only preflight failed: HTTP " + response.status() +
                    (body.isBlank() ? "" : " body=" + body));
        }

        System.out.println("[MinecraftPreflight] PASS MinecraftLobby is accessible with the Bedrock authentication chain.");
        System.out.println("[MinecraftPreflight] Continuing with RTA + Minecraft session-document validation...");
        MinecraftSessionPreflight.run(config);
        System.out.println("[MinecraftPreflight] Continuing with Xbox-RPC NetherNet signaling validation...");
        XboxRpcSignalingPreflight.run(config);
        System.out.println("[MinecraftPreflight] PASS all read-only Minecraft broadcast prerequisites completed.");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sanitize(String body) {
        if (body == null) return "";
        String cleaned = body.replaceAll("(?i)XBL3\\.0[^\\\"\\s]*", "[redacted]")
                .replaceAll("(?i)Bearer\\s+[^\\\"\\s]+", "Bearer [redacted]")
                .replaceAll("(?i)MCToken\\s+[^\\\"\\s]+", "MCToken [redacted]")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
        if (cleaned.length() > 800) cleaned = cleaned.substring(0, 800) + "…";
        return cleaned;
    }
}
