package uk.blazecraft.novabroadcast;

final class XboxPresenceSelfTest {
    static void run() {
        String json = "{" +
                "\"xuid\":\"123\",\"state\":\"Online\"," +
                "\"devices\":[{\"type\":\"XboxOne\",\"titles\":[{" +
                "\"id\":\"456\",\"name\":\"Minecraft\",\"state\":\"Active\"," +
                "\"timestamp\":\"2026-08-28T00:00:00Z\"}]}]}";
        XboxPresenceClient.Presence presence = XboxPresenceClient.parse(json);
        require("Online".equals(presence.state()), "presence account state");
        require(presence.titles().size() == 1, "presence title count");
        XboxPresenceClient.Title title = presence.titles().get(0);
        require("456".equals(title.id()), "presence title id");
        require("Minecraft".equals(title.name()), "presence title name");
        require("Active".equals(title.state()), "presence title state");
        System.out.println("[SelfTest] Xbox presence parser passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Self-test failed: " + message);
    }

    private XboxPresenceSelfTest() {}
}
