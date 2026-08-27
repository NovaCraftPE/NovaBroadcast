package uk.blazecraft.novabroadcast;

/**
 * Builds the minimal authenticated-member portion of an MPSD session write.
 *
 * Title-specific constants/custom properties belong to the configured session
 * template and later NovaBroadcast Minecraft/NetherNet milestones. This class
 * intentionally does not embed Minecraft-owned session data.
 */
final class SessionDocument {
    static String activeMember(XboxIdentity identity) {
        if (identity.xuid() == null || identity.xuid().isBlank()) {
            throw new IllegalStateException("Xbox identity does not contain an XUID.");
        }

        return "{" +
                "\"members\":{" +
                    "\"me\":{" +
                        "\"constants\":{" +
                            "\"system\":{" +
                                "\"xuid\":" + Json.quote(identity.xuid()) +
                            "}" +
                        "}," +
                        "\"properties\":{" +
                            "\"system\":{" +
                                "\"active\":true" +
                            "}" +
                        "}" +
                    "}" +
                "}" +
            "}";
    }

    private SessionDocument() {}
}
