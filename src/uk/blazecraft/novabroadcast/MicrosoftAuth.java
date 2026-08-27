package uk.blazecraft.novabroadcast;

import java.util.Map;

final class MicrosoftAuth {
    private final AppConfig config;
    private final TokenStore store;

    MicrosoftAuth(AppConfig config, TokenStore store) {
        this.config = config;
        this.store = store;
    }

    MicrosoftTokens getTokens() throws Exception {
        MicrosoftTokens cached = store.load();
        if (cached != null) {
            if (!cached.expiresSoon() && !cached.accessToken().isBlank()) return cached;
            try {
                MicrosoftTokens refreshed = refresh(cached.refreshToken());
                store.save(refreshed);
                return refreshed;
            } catch (Exception e) {
                System.out.println("[Auth] Cached refresh token was rejected; starting device login.");
            }
        }

        MicrosoftTokens fresh = deviceFlow();
        store.save(fresh);
        return fresh;
    }

    private MicrosoftTokens refresh(String refreshToken) throws Exception {
        String url = "https://login.microsoftonline.com/" + config.tenant() + "/oauth2/v2.0/token";
        String body = Form.encode(Map.of(
                "client_id", config.clientId(),
                "scope", config.scope(),
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        ));
        Http.Response r = Http.post(url, body, Map.of("Content-Type","application/x-www-form-urlencoded"));
        r.requireOk("Microsoft refresh");
        return tokenResponse(r.body());
    }

    private MicrosoftTokens deviceFlow() throws Exception {
        String base = "https://login.microsoftonline.com/" + config.tenant() + "/oauth2/v2.0";
        Http.Response dc = Http.post(base + "/devicecode",
                Form.encode(Map.of("client_id", config.clientId(), "scope", config.scope())),
                Map.of("Content-Type","application/x-www-form-urlencoded"));
        dc.requireOk("Microsoft device-code request");

        String deviceCode = Json.string(dc.body(), "device_code");
        String userCode = Json.string(dc.body(), "user_code");
        String verify = Json.string(dc.body(), "verification_uri");
        String message = Json.string(dc.body(), "message");
        int interval = intField(dc.body(), "interval", 5);
        int expires = intField(dc.body(), "expires_in", 900);

        System.out.println("[Auth] " + (message.isBlank()
                ? "Open " + verify + " and enter code " + userCode
                : message));

        long deadline = System.currentTimeMillis() + expires * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            Http.Response tr = Http.post(base + "/token",
                    Form.encode(Map.of(
                            "grant_type","urn:ietf:params:oauth:grant-type:device_code",
                            "client_id",config.clientId(),
                            "device_code",deviceCode
                    )),
                    Map.of("Content-Type","application/x-www-form-urlencoded"));

            if (tr.ok()) return tokenResponse(tr.body());

            String error = Json.string(tr.body(), "error");
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) { interval += 5; continue; }
            throw new IllegalStateException("Microsoft device login failed: HTTP " + tr.status() + " - " + tr.body());
        }
        throw new IllegalStateException("Microsoft device login expired.");
    }

    private MicrosoftTokens tokenResponse(String body) {
        String access = Json.string(body, "access_token");
        String refresh = Json.string(body, "refresh_token");
        int expires = intField(body, "expires_in", 3600);
        if (access.isBlank()) throw new IllegalStateException("Microsoft token response did not include access_token.");
        return new MicrosoftTokens(access, refresh, System.currentTimeMillis()/1000L + expires);
    }

    private static int intField(String json, String key, int fallback) {
        Object root = Json.parse(json);
        if (root instanceof Map<?,?> map) {
            Object v = map.get(key);
            if (v instanceof Number n) return n.intValue();
            try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        }
        return fallback;
    }
}
