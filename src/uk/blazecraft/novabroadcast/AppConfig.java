package uk.blazecraft.novabroadcast;

import java.io.*;
import java.nio.file.*;
import java.util.*;

record AppConfig(
        String clientId,
        String clientSecret,
        String redirectUri,
        String microsoftCallbackListenHost,
        int microsoftCallbackListenPort,
        int microsoftCallbackTimeoutSeconds,
        String scope,
        String tenant,
        String xboxRelyingParty,
        String xboxSandboxId,
        String targetHost,
        int targetPort,
        String targetName,
        boolean sessionEnabled,
        boolean sessionWriteEnabled,
        boolean sessionSetActivity,
        boolean netherNetEnabled,
        String sessionScid,
        String sessionTemplate,
        String sessionName,
        String sessionCustomPropertiesFile,
        String sessionMemberCustomPropertiesFile,
        String netherNetListenHost,
        int netherNetListenPort,
        int netherNetMaxSdpBytes,
        int netherNetMaxSctpMessageSize,
        String netherNetStunUrl,
        int netherNetIceMinPort,
        int netherNetIceMaxPort,
        String netherNetIdentityKey,
        String netherNetIdentityDomain,
        boolean netherNetRequireClientIdentity,
        String netherNetClientIssuer,
        String netherNetClientAudience,
        String netherNetClientJwksUrl,
        boolean bedrockRedirectEnabled,
        String bedrockGameVersion) {

    static AppConfig load(Path path) throws IOException {
        if (!Files.exists(path)) Files.writeString(path, DefaultConfig.TEXT);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }

        int iceMinPort = parsePort(p, "nethernet.iceMinPort", 20000);
        int iceMaxPort = parsePort(p, "nethernet.iceMaxPort", 20100);
        if (iceMinPort > iceMaxPort) throw new IllegalArgumentException("nethernet.iceMinPort must be <= nethernet.iceMaxPort");

        return new AppConfig(
                p.getProperty("microsoft.clientId", "").trim(),
                p.getProperty("microsoft.clientSecret", "").trim(),
                p.getProperty("microsoft.redirectUri", "").trim(),
                p.getProperty("microsoft.callbackListenHost", "0.0.0.0").trim(),
                parsePort(p, "microsoft.callbackListenPort", 53682),
                parsePositive(p, "microsoft.callbackTimeoutSeconds", 300),
                normalizeMicrosoftScope(p.getProperty("microsoft.scope", "xboxlive.signin xboxlive.offline_access")),
                p.getProperty("microsoft.tenant", "consumers").trim(),
                p.getProperty("xbox.relyingParty", "http://xboxlive.com").trim(),
                p.getProperty("xbox.sandboxId", "RETAIL").trim(),
                p.getProperty("target.host", "127.0.0.1").trim(),
                parsePort(p, "target.port", 19132),
                p.getProperty("target.name", "NovaCraft").trim(),
                Boolean.parseBoolean(p.getProperty("session.enabled", "false")),
                Boolean.parseBoolean(p.getProperty("session.writeEnabled", "false")),
                Boolean.parseBoolean(p.getProperty("session.setActivity", "false")),
                Boolean.parseBoolean(p.getProperty("nethernet.enabled", "false")),
                p.getProperty("session.scid", "").trim(),
                p.getProperty("session.template", "").trim(),
                p.getProperty("session.name", "NovaBroadcast").trim(),
                p.getProperty("session.customPropertiesFile", "").trim(),
                p.getProperty("session.memberCustomPropertiesFile", "").trim(),
                p.getProperty("nethernet.listenHost", "0.0.0.0").trim(),
                parsePort(p, "nethernet.listenPort", 19134),
                parsePositive(p, "nethernet.maxSdpBytes", 1_048_576),
                parsePositive(p, "nethernet.maxSctpMessageSize", 262_144),
                p.getProperty("nethernet.stunUrl", "stun:stun.l.google.com:19302").trim(),
                iceMinPort,
                iceMaxPort,
                p.getProperty("nethernet.identityKey", "data/nethernet-identity.key").trim(),
                p.getProperty("nethernet.identityDomain", "self").trim(),
                Boolean.parseBoolean(p.getProperty("nethernet.requireClientIdentity", "false")),
                p.getProperty("nethernet.clientIssuer", MinecraftClientIdentityVerifier.DEFAULT_ISSUER).trim(),
                p.getProperty("nethernet.clientAudience", MinecraftClientIdentityVerifier.DEFAULT_AUDIENCE).trim(),
                p.getProperty("nethernet.clientJwksUrl", "").trim(),
                Boolean.parseBoolean(p.getProperty("bedrock.redirectEnabled", "false")),
                p.getProperty("bedrock.gameVersion", "1.26.44").trim()
        );
    }

    private static String normalizeMicrosoftScope(String raw) {
        if (raw == null || raw.isBlank()) return "xboxlive.signin xboxlive.offline_access";
        String[] parts = raw.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("xboxlive.signin")) parts[i] = "xboxlive.signin";
            else if (parts[i].equalsIgnoreCase("xboxlive.offline_access")) parts[i] = "xboxlive.offline_access";
        }
        return String.join(" ", parts);
    }

    private static int parsePort(Properties p, String key, int fallback) {
        int value = parsePositive(p, key, fallback);
        if (value > 65535) throw new IllegalArgumentException(key + " must be <= 65535");
        return value;
    }

    private static int parsePositive(Properties p, String key, int fallback) {
        String raw = p.getProperty(key, Integer.toString(fallback)).trim();
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) throw new IllegalArgumentException(key + " must be > 0");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw);
        }
    }
}

final class DefaultConfig {
    static final String TEXT = """
# NovaBroadcast clean-room configuration
# Xbox website sign-in uses Microsoft's documented authorization-code flow.
# microsoft.redirectUri must be the public HTTPS callback registered on the Entra app.
# Point that URL through your reverse proxy to callbackListenHost:callbackListenPort.
microsoft.clientId=
microsoft.clientSecret=
microsoft.redirectUri=
microsoft.callbackListenHost=0.0.0.0
microsoft.callbackListenPort=53682
microsoft.callbackTimeoutSeconds=300
microsoft.scope=xboxlive.signin xboxlive.offline_access
microsoft.tenant=consumers
xbox.relyingParty=http://xboxlive.com
# Use RETAIL unless Partner Center has explicitly assigned an authorized development sandbox.
xbox.sandboxId=RETAIL

target.host=54.37.245.44
target.port=19133
target.name=NovaCraft

# Bedrock redirect bootstrap. Leave disabled until NetherNet signaling is
# reachable and you are ready to test a real supported Bedrock client.
bedrock.redirectEnabled=false
bedrock.gameVersion=1.26.44

# Xbox Multiplayer Session Directory (MPSD)
session.enabled=false
session.scid=
session.template=
session.name=NovaBroadcast
session.writeEnabled=false
# When true, a successful live publication is also bound as the signed-in
# account's Xbox activity handle (join-in-progress/social activity).
session.setActivity=false

# Optional title-authorized JSON objects. NovaBroadcast never guesses Minecraft
# custom property names. Supply these only from your legitimate title/session
# integration data. Files must each contain one JSON object.
session.customPropertiesFile=
session.memberCustomPropertiesFile=

# NetherNet/WebRTC.
nethernet.enabled=false
nethernet.listenHost=0.0.0.0
nethernet.listenPort=19134
nethernet.maxSdpBytes=1048576
nethernet.maxSctpMessageSize=262144

# Long-lived server/operator identity.
nethernet.identityKey=data/nethernet-identity.key
nethernet.identityDomain=self

# Authenticated client admission. NovaBroadcast uses the issuer's OpenID
# discovery document to obtain the current JWKS URI and expected issuer.
# clientJwksUrl is an optional explicit override for testing/other environments.
nethernet.requireClientIdentity=false
nethernet.clientIssuer=https://authorization.franchise.minecraft-services.net/
nethernet.clientAudience=api://auth-minecraft-services/multiplayer
nethernet.clientJwksUrl=

nethernet.stunUrl=stun:stun.l.google.com:19302
nethernet.iceMinPort=20000
nethernet.iceMaxPort=20100
""";
    private DefaultConfig() {}
}
