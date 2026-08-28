package uk.blazecraft.novabroadcast;

import java.nio.file.*;
import java.util.Map;

/** Offline/low-impact deployment preflight that does not perform Microsoft/Xbox sign-in. */
final class ConfigCheck {
    static void run(Path configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        int warnings = 0;

        System.out.println("[ConfigCheck] Loaded " + configPath.toAbsolutePath());
        ok("target", config.targetHost() + ":" + config.targetPort());
        if (config.targetHost().isBlank()) fail("target.host is blank");

        if (config.bedrockRedirectEnabled()) {
            int protocol = BedrockProtocolVersions.requireProtocol(config.bedrockGameVersion());
            ok("Bedrock redirect", config.bedrockGameVersion() + " / protocol " + protocol);
            if (!config.netherNetEnabled()) fail("bedrock.redirectEnabled=true requires nethernet.enabled=true");
        } else {
            warn("Bedrock redirect is disabled; real clients will not be redirected");
            warnings++;
        }

        if (config.netherNetEnabled()) {
            ok("NetherNet signaling", config.netherNetListenHost() + ":" + config.netherNetListenPort());
            ok("ICE UDP range", config.netherNetIceMinPort() + "-" + config.netherNetIceMaxPort());
            if (config.netherNetIceMaxPort() - config.netherNetIceMinPort() < 4) {
                warn("ICE UDP range is very small; allocate a wider mapped range for concurrent clients");
                warnings++;
            }
            Path key = Path.of(config.netherNetIdentityKey());
            Path parent = key.toAbsolutePath().getParent();
            if (Files.exists(key)) {
                ok("Operator identity key", key.toString() + " (existing)");
            } else if (parent != null && (Files.exists(parent) ? Files.isWritable(parent) : writableAncestor(parent))) {
                ok("Operator identity key", key.toString() + " (will be generated)");
            } else {
                fail("Operator identity key path is not writable: " + key);
            }

            MinecraftClientIdentityVerifier verifier = new MinecraftClientIdentityVerifier(
                    config.netherNetClientIssuer(), config.netherNetClientAudience(), config.netherNetClientJwksUrl());
            ok("Minecraft auth issuer", verifier.issuer());
            ok("Minecraft auth JWKS", verifier.jwksUrl());
        } else {
            warn("NetherNet is disabled");
            warnings++;
        }

        validateJsonObject(config.sessionCustomPropertiesFile(), "session custom properties");
        validateJsonObject(config.sessionMemberCustomPropertiesFile(), "member custom properties");

        if (config.sessionWriteEnabled()) {
            if (!config.sessionEnabled()) fail("session.writeEnabled=true requires session.enabled=true");
            if (config.sessionScid().isBlank()) fail("session.scid is blank");
            if (config.sessionTemplate().isBlank()) fail("session.template is blank");
            if (config.sessionCustomPropertiesFile().isBlank()) {
                fail("live MPSD publication requires session.customPropertiesFile");
            }
            if (!config.bedrockRedirectEnabled()) fail("live MPSD publication requires bedrock.redirectEnabled=true");
            if (!config.netherNetEnabled()) fail("live MPSD publication requires nethernet.enabled=true");
            if (config.sessionSetActivity()) ok("Xbox activity binding", "enabled after successful publication");
            else {
                warn("session.setActivity=false; the session will be published but not bound as the account's current activity");
                warnings++;
            }
        } else {
            warn("MPSD live publication is disabled");
            warnings++;
            if (config.sessionSetActivity()) {
                warn("session.setActivity=true has no effect while session.writeEnabled=false");
                warnings++;
            }
        }

        if (config.clientId().isBlank()) {
            warn("microsoft.clientId is blank; normal startup cannot authenticate yet");
            warnings++;
        } else {
            ok("Microsoft client ID", "configured");
        }

        System.out.println("[ConfigCheck] PASS" + (warnings == 0 ? "" : " with " + warnings + " warning(s)"));
    }

    private static void validateJsonObject(String file, String label) throws Exception {
        if (file == null || file.isBlank()) return;
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) fail(label + " file not found: " + path);
        Object parsed = Json.parse(Files.readString(path));
        if (!(parsed instanceof Map<?,?>)) fail(label + " file must contain one JSON object: " + path);
        ok(label, path.toString());
    }

    private static boolean writableAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) current = current.getParent();
        return current != null && Files.isDirectory(current) && Files.isWritable(current);
    }

    private static void ok(String name, String value) {
        System.out.println("[ConfigCheck] OK   " + name + ": " + value);
    }

    private static void warn(String message) {
        System.out.println("[ConfigCheck] WARN " + message);
    }

    private static void fail(String message) {
        throw new IllegalStateException("[ConfigCheck] FAIL " + message);
    }

    private ConfigCheck() {}
}
