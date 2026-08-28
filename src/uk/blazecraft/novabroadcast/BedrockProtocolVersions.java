package uk.blazecraft.novabroadcast;

import java.util.Map;
import java.util.OptionalInt;

/** Explicit protocol mappings for versions whose redirect packet layout is verified. */
final class BedrockProtocolVersions {
    private static final Map<String,Integer> SUPPORTED = Map.of(
            "1.26.40", 2168,
            "1.26.43", 2168,
            "1.26.44", 2168,
            "1.26.45", 2169
    );

    static OptionalInt protocolFor(String gameVersion) {
        Integer value = SUPPORTED.get(gameVersion == null ? "" : gameVersion.trim());
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    static int requireProtocol(String gameVersion) {
        return protocolFor(gameVersion).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported bedrock.gameVersion for redirect bootstrap: " + gameVersion +
                ". Verified versions: " + String.join(", ", SUPPORTED.keySet())));
    }

    static boolean matches(String gameVersion, int protocol) {
        OptionalInt configured = protocolFor(gameVersion);
        return configured.isPresent() && configured.getAsInt() == protocol;
    }

    private BedrockProtocolVersions() {}
}
