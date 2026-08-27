package uk.blazecraft.novabroadcast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clean-room Xbox Multiplayer Session Directory client.
 *
 * Title-specific Minecraft constants/custom properties and NetherNet network
 * identity are intentionally not embedded here. Until those are implemented,
 * NovaBroadcast can validate and render the base session write but refuses to
 * publish a misleading joinable session.
 */
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
        validateTemplate(config);
        System.out.println("[Session] MPSD template is reachable with the authenticated account.");
        System.out.println("[Session] Session URI: " + sessionUri(config));

        String document = SessionDocument.activeMember(identity);
        System.out.println("[Session] Base session document: " + document);

        if (!config.sessionWriteEnabled()) {
            System.out.println("[Session] Dry-run complete. session.writeEnabled=false, so no MPSD session was written.");
            return;
        }

        if (!config.netherNetEnabled()) {
            throw new UnsupportedOperationException(
                    "Live MPSD writes are blocked until NetherNet is enabled and its Minecraft session fields are implemented. " +
                    "This prevents NovaBroadcast from advertising an unreachable session.");
        }

        throw new UnsupportedOperationException(
                "NetherNet/WebRTC transport is not implemented yet; refusing to publish the session.");
    }

    /**
     * Lifecycle primitive for the later transport milestone. Creates a new
     * session only; If-None-Match prevents accidentally replacing an existing
     * session with the same name.
     */
    Http.Response create(AppConfig config, String document) throws Exception {
        Map<String,String> h = writeHeaders();
        h.put("If-None-Match", "*");
        return Http.put(sessionUri(config), document, h);
    }

    Http.Response read(AppConfig config) throws Exception {
        return Http.get(sessionUri(config), headers());
    }

    /** Remove the authenticated caller from the session. */
    Http.Response leave(AppConfig config) throws Exception {
        return Http.delete(sessionUri(config) + "/members/me", headers());
    }

    private void validateTemplate(AppConfig config) throws Exception {
        String url = MPSD + "/serviceconfigs/" + encode(config.sessionScid()) +
                "/sessiontemplates/" + encode(config.sessionTemplate());
        Http.Response response = Http.get(url, headers());
        if (!response.ok()) {
            throw new IllegalStateException(
                    "MPSD template preflight failed: HTTP " + response.status() +
                    ". Check that session.scid/session.template are correct and authorized for this account/title.");
        }
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
        if (config.sessionScid().isBlank()) {
            throw new IllegalStateException("session.enabled=true but session.scid is blank.");
        }
        if (config.sessionTemplate().isBlank()) {
            throw new IllegalStateException("session.enabled=true but session.template is blank.");
        }
        if (config.sessionName().isBlank()) {
            throw new IllegalStateException("session.enabled=true but session.name is blank.");
        }
    }

    static String sessionUri(AppConfig config) {
        return MPSD + "/serviceconfigs/" + encode(config.sessionScid()) +
                "/sessiontemplates/" + encode(config.sessionTemplate()) +
                "/sessions/" + encode(config.sessionName());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
