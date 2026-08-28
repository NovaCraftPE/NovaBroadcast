package uk.blazecraft.novabroadcast;

import java.util.Map;

/** Builds the authenticated member/session portions of an MPSD write. */
final class SessionDocument {
    static String activeMember(XboxIdentity identity) {
        return activeMember(identity, "{}", "{}");
    }

    static String activeMember(XboxIdentity identity, String sessionCustomJson, String memberCustomJson) {
        if (identity.xuid() == null || identity.xuid().isBlank()) {
            throw new IllegalStateException("Xbox identity does not contain an XUID.");
        }
        requireObject(sessionCustomJson, "session custom properties");
        requireObject(memberCustomJson, "member custom properties");

        return "{" +
                "\"properties\":{" +
                    "\"custom\":" + sessionCustomJson +
                "}," +
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
                            "}," +
                            "\"custom\":" + memberCustomJson +
                        "}" +
                    "}" +
                "}" +
            "}";
    }

    private static void requireObject(String json, String label) {
        Object value = Json.parse(json);
        if (!(value instanceof Map<?,?>)) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
    }

    private SessionDocument() {}
}
