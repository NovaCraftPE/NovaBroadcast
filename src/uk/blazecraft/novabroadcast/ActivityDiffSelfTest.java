package uk.blazecraft.novabroadcast;

import java.util.List;

/** Deterministic tests for activity-aware MPSD comparison. */
final class ActivityDiffSelfTest {
    static void run() {
        String first = "{\"results\":[" +
                activity("2", "scid-b", "tmpl", "session-b1", "stable", "dyn-b1") + "," +
                activity("1", "scid-a", "tmpl", "session-a1", "stable", "dyn-a1") + "]}";
        String second = "{\"results\":[" +
                activity("1", "scid-a", "tmpl", "session-a2", "stable", "dyn-a2") + "," +
                activity("2", "scid-b", "tmpl", "session-b1", "stable", "dyn-b1") + "]}";

        List<ActivityDiff.Change> changes = ActivityDiff.compare(first, second);
        require(changes.size() == 2, "reordering must not create false changes");
        require(changes.stream().anyMatch(c -> c.kind() == ActivityDiff.Kind.SESSION_REF && c.path().endsWith(".sessionRef.name")),
                "changed session name classification");
        require(changes.stream().anyMatch(c -> c.kind() == ActivityDiff.Kind.CUSTOM && c.path().endsWith(".properties.custom.dynamic")),
                "changed custom property classification");
        require(changes.stream().noneMatch(c -> c.path().contains("scid-b") && !c.path().contains("title=2")),
                "unmodified second activity must not create index noise");
        System.out.println("[SelfTest] Activity-aware MPSD diff passed.");
    }

    private static String activity(String title, String scid, String template, String name, String stable, String dynamic) {
        return "{" +
                "\"titleId\":" + Json.quote(title) + "," +
                "\"sessionRef\":{" +
                    "\"scid\":" + Json.quote(scid) + "," +
                    "\"templateName\":" + Json.quote(template) + "," +
                    "\"name\":" + Json.quote(name) + "}," +
                "\"session\":{\"properties\":{\"custom\":{" +
                    "\"stable\":" + Json.quote(stable) + "," +
                    "\"dynamic\":" + Json.quote(dynamic) + "}}}" +
                "}";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Self-test failed: " + message);
    }

    private ActivityDiffSelfTest() {}
}
