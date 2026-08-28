package uk.blazecraft.novabroadcast;

import java.util.*;

/** Read-only Xbox Presence diagnostic for the authenticated account. */
final class XboxPresenceClient {
    private static final String URL = "https://userpresence.xboxlive.com/users/me?level=all";
    private final XboxIdentity identity;

    XboxPresenceClient(XboxIdentity identity) {
        this.identity = identity;
    }

    Presence read() throws Exception {
        Http.Response response = Http.get(URL, Map.of(
                "Authorization", identity.authorizationHeader(),
                "x-xbl-contract-version", "3",
                "Accept", "application/json",
                "Accept-Language", "en-US"));
        response.requireOk("Xbox Presence query");
        return parse(response.body());
    }

    static Presence parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?,?> map)) throw new IllegalArgumentException("Presence response is not a JSON object");
        String state = value(map.get("state"));
        List<Title> titles = new ArrayList<>();
        Object devicesObj = map.get("devices");
        if (devicesObj instanceof List<?> devices) {
            for (Object deviceObj : devices) {
                if (!(deviceObj instanceof Map<?,?> device)) continue;
                String deviceType = value(device.get("type"));
                Object titlesObj = device.get("titles");
                if (!(titlesObj instanceof List<?> titleList)) continue;
                for (Object titleObj : titleList) {
                    if (!(titleObj instanceof Map<?,?> title)) continue;
                    titles.add(new Title(
                            value(title.get("id")),
                            value(title.get("name")),
                            value(title.get("state")),
                            deviceType,
                            value(title.get("timestamp"))));
                }
            }
        }
        return new Presence(state, List.copyOf(titles));
    }

    static void print(Presence presence) {
        System.out.println("[Presence] Account state=" + presence.state());
        if (presence.titles().isEmpty()) {
            System.out.println("[Presence] No active title records were returned.");
            return;
        }
        for (Title title : presence.titles()) {
            System.out.println("[Presence] titleId=" + title.id() +
                    (title.name().isBlank() ? "" : " name=" + title.name()) +
                    (title.state().isBlank() ? "" : " state=" + title.state()) +
                    (title.deviceType().isBlank() ? "" : " device=" + title.deviceType()));
        }
    }

    record Presence(String state, List<Title> titles) {}
    record Title(String id, String name, String state, String deviceType, String timestamp) {}

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
