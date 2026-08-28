package uk.blazecraft.novabroadcast;

import java.util.Map;
import java.util.OptionalInt;

/** Explicit protocol mappings for versions whose redirect packet layout is tested. */
final class BedrockProtocolVersions {
    private static final Map<String,Integer> SUPPORTED = Map.of(
            "1.26.40", 2168,
            "1.26.43", 2168,
            "1.26.44", 2168
    );

    static OptionalInt protocolFor(String gameVersion) {
        Integer value = SUPPORTED.get(gameVersion == null ? "" : gameVersion.trim());
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    static int requireProtocol(String gameVersion) {
        return protocolFor(gameVersion).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported bedrock.gameVersion for redirect bootstrap: " + gameVersion +
                ". Tested versions: " + String.join(", ", SUPPORTED.keySet())));
    }

    static boolean matches(String gameVersion, int protocol) {
        OptionalInt configured = protocolFor(gameVersion);
        return configured.isPresent() && configured.getAsInt() == protocol;
    }

    private BedrockProtocolVersions() {}
}
