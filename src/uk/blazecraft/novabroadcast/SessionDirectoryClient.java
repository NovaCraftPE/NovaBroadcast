package uk.blazecraft.novabroadcast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Clean-room Xbox Multiplayer Session Directory boundary.
 *
 * This milestone deliberately stops before writing a Minecraft session. It
 * validates the configured, title-authorized SCID/template against MPSD and
 * only then reports that the application is ready for the session-document
 * implementation. No Minecraft Retail identifiers are embedded here.
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

        if (config.netherNetEnabled()) {
            throw new UnsupportedOperationException(
                    "NetherNet/WebRTC transport is not implemented yet. " +
                    "Disable nethernet.enabled until the transport milestone is complete.");
        }

        System.out.println("[Session] Session writes remain disabled until the Minecraft session document is implemented.");
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
        return Map.of(
                "Authorization", identity.authorizationHeader(),
                "Accept", "application/json",
                "x-xbl-contract-version", "107"
        );
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
