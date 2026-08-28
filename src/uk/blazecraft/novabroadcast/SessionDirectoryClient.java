package uk.blazecraft.novabroadcast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Clean-room Xbox Multiplayer Session Directory client. */
final class SessionDirectoryClient {
    private static final String MPSD = "https://sessiondirectory.xboxlive.com";
    private final XboxIdentity identity;

    SessionDirectoryClient(XboxIdentity identity) {
        this.identity = identity;
    }

    void start(AppConfig config) throws Exception {
        requireConfigured(config);

        System.out.println();
        System.out.println("[Session] Running MPSD preflight...");
        TemplateInfo template = validateTemplate(config);
        System.out.println("[Session] MPSD template is reachable with the authenticated account.");
        System.out.println("[Session] Template visibility=" + template.visibility() +
                " connectivity=" + template.connectivity() + " gameplay=" + template.gameplay());
        System.out.println("[Session] Session URI: " + sessionUri(config));

        String sessionCustom = readJsonObject(config.sessionCustomPropertiesFile(), "session custom properties");
        String memberCustom = readJsonObject(config.sessionMemberCustomPropertiesFile(), "member custom properties");
        String document = SessionDocument.activeMember(identity, sessionCustom, memberCustom);
        System.out.println("[Session] Session document prepared.");

        if (!config.sessionWriteEnabled()) {
            System.out.println("[Session] Dry-run complete. session.writeEnabled=false, so no MPSD session was written.");
            if (config.sessionSetActivity()) {
                System.out.println("[Session] session.setActivity=true ignored during dry-run.");
            }
            return;
        }

        if (!config.netherNetEnabled()) {
            throw new IllegalStateException("Live MPSD writes require nethernet.enabled=true.");
        }
        if (!config.bedrockRedirectEnabled()) {
            throw new IllegalStateException("Live MPSD writes require bedrock.redirectEnabled=true so joined clients have a working redirect path.");
        }
        if (!template.connectivity()) {
            throw new IllegalStateException("The selected MPSD template does not advertise connectivity capability.");
        }
        if (config.sessionCustomPropertiesFile().isBlank()) {
            throw new IllegalStateException(
                    "Live MPSD publication requires session.customPropertiesFile with title-authorized Minecraft session metadata. " +
                    "NovaBroadcast will not invent private Minecraft custom-property names.");
        }

        Http.Response created = create(config, document);
        if (!created.ok()) {
            throw new IllegalStateException("MPSD session create failed: HTTP " + created.status() + " " + created.body());
        }
        System.out.println("[Session] MPSD session published successfully.");

        if (config.sessionSetActivity()) {
            Http.Response activity = setActivity(config);
            if (!activity.ok()) {
                throw new IllegalStateException("MPSD activity handle create failed: HTTP " + activity.status() + " " + activity.body());
            }
            System.out.println("[Session] Xbox activity handle now points to the published session.");
        } else {
            System.out.println("[Session] Activity handle not changed (session.setActivity=false).");
        }
    }

    void dumpOwnActivities(Path output) throws Exception {
        if (identity.xuid() == null || identity.xuid().isBlank()) {
            throw new IllegalStateException("Xbox identity does not contain an XUID.");
        }
        String url = MPSD + "/handles/query?include=relatedInfo,session" +
                "&xuid=" + encode(identity.xuid()) +
                "&private=true&inactive=true&reservations=true&take=100";
        Http.Response response = Http.post(url, "{\"type\":\"activity\"}", writeHeaders());
        response.requireOk("MPSD own-activity query");

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, response.body(), StandardCharsets.UTF_8);
        System.out.println("[Session] Saved authenticated activity dump to " + output.toAbsolutePath());
        printActivitySummary(response.body());
    }

    Http.Response create(AppConfig config, String document) throws Exception {
        Map<String,String> h = writeHeaders();
        h.put("If-None-Match", "*");
        return Http.put(sessionUri(config), document, h);
    }

    Http.Response read(AppConfig config) throws Exception {
        return Http.get(sessionUri(config), headers());
    }

    Http.Response leave(AppConfig config) throws Exception {
        return Http.delete(sessionUri(config) + "/members/me", headers());
    }

    Http.Response setActivity(AppConfig config) throws Exception {
        return Http.post(MPSD + "/handles", activityHandleDocument(config), writeHeaders());
    }

    static String activityHandleDocument(AppConfig config) {
        return "{" +
                "\"version\":1," +
                "\"type\":\"activity\"," +
                "\"sessionRef\":{" +
                    "\"scid\":" + Json.quote(config.sessionScid()) + "," +
                    "\"templateName\":" + Json.quote(config.sessionTemplate()) + "," +
                    "\"name\":" + Json.quote(config.sessionName()) +
                "}" +
            "}";
    }

    static int activityCount(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?,?> map)) return 0;
        Object results = map.get("results");
        return results instanceof List<?> list ? list.size() : 0;
    }

    private static void printActivitySummary(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?,?> map) || !(map.get("results") instanceof List<?> results)) {
            System.out.println("[Session] Activity query returned no parseable results array.");
            return;
        }
        System.out.println("[Session] Activity handles found: " + results.size());
        for (Object item : results) {
            if (!(item instanceof Map<?,?> handle)) continue;
            Object refObj = handle.get("sessionRef");
            if (!(refObj instanceof Map<?,?> ref)) continue;
            String scid = value(ref.get("scid"));
            String template = value(ref.get("templateName"));
            String name = value(ref.get("name"));
            String titleId = value(handle.get("titleId"));
            System.out.println("[Session] Activity" +
                    (titleId.isBlank() ? "" : " titleId=" + titleId) +
                    " scid=" + scid + " template=" + template + " name=" + name);
        }
    }

    private TemplateInfo validateTemplate(AppConfig config) throws Exception {
        String url = MPSD + "/serviceconfigs/" + encode(config.sessionScid()) +
                "/sessiontemplates/" + encode(config.sessionTemplate());
        Http.Response response = Http.get(url, headers());
        if (!response.ok()) {
            throw new IllegalStateException(
                    "MPSD template preflight failed: HTTP " + response.status() +
                    ". Check that session.scid/session.template are correct and authorized for this account/title.");
        }
        return parseTemplate(response.body());
    }

    private static TemplateInfo parseTemplate(String json) {
        try {
            Object root = Json.parse(json);
            if (!(root instanceof Map<?,?> map)) return new TemplateInfo("unknown", false, false);
            Object constantsObj = map.get("constants");
            if (!(constantsObj instanceof Map<?,?> constants)) return new TemplateInfo("unknown", false, false);
            Object systemObj = constants.get("system");
            if (!(systemObj instanceof Map<?,?> system)) return new TemplateInfo("unknown", false, false);
            Object visibilityObj = system.get("visibility");
            String visibility = visibilityObj == null ? "open" : String.valueOf(visibilityObj);
            boolean connectivity = false;
            boolean gameplay = false;
            Object capabilitiesObj = system.get("capabilities");
            if (capabilitiesObj instanceof Map<?,?> capabilities) {
                connectivity = Boolean.TRUE.equals(capabilities.get("connectivity"));
                gameplay = Boolean.TRUE.equals(capabilities.get("gameplay"));
            }
            return new TemplateInfo(visibility, connectivity, gameplay);
        } catch (RuntimeException e) {
            return new TemplateInfo("unknown", false, false);
        }
    }

    private static String readJsonObject(String file, String label) throws Exception {
        if (file == null || file.isBlank()) return "{}";
        Path path = Path.of(file);
        if (!Files.exists(path)) throw new IllegalStateException(label + " file not found: " + path);
        String json = Files.readString(path).trim();
        Object value = Json.parse(json);
        if (!(value instanceof Map<?,?>)) throw new IllegalStateException(label + " file must contain one JSON object: " + path);
        return json;
    }

    private Map<String,String> headers() {
        Map<String,String> h = new LinkedHashMap<>();
        h.put("Authorization", identity.authorizationHeader());
        h.put("Accept", "application/json");
        h.put("x-xbl-contract-version", "107");
        return h;
    }

    private Map<String,String> writeHeaders() {
        Map<String,String> h = new LinkedHashMap<>(headers());
        h.put("Content-Type", "application/json");
        return h;
    }

    private static void requireConfigured(AppConfig config) {
        if (config.sessionScid().isBlank()) throw new IllegalStateException("session.enabled=true but session.scid is blank.");
        if (config.sessionTemplate().isBlank()) throw new IllegalStateException("session.enabled=true but session.template is blank.");
        if (config.sessionName().isBlank()) throw new IllegalStateException("session.enabled=true but session.name is blank.");
    }

    static String sessionUri(AppConfig config) {
        return MPSD + "/serviceconfigs/" + encode(config.sessionScid()) +
                "/sessiontemplates/" + encode(config.sessionTemplate()) +
                "/sessions/" + encode(config.sessionName());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private record TemplateInfo(String visibility, boolean connectivity, boolean gameplay) {}
}
