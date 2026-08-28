package uk.blazecraft.novabroadcast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
            String visibility = String.valueOf(system.getOrDefault("visibility", "open"));
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

    private record TemplateInfo(String visibility, boolean connectivity, boolean gameplay) {}
}
