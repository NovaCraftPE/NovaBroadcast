package uk.blazecraft.novabroadcast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class MicrosoftAuth {
    private final AppConfig config;
    private final TokenStore store;

    MicrosoftAuth(AppConfig config, TokenStore store) {
        this.config = config;
        this.store = store;
    }

    MicrosoftTokens getTokens() throws Exception {
        requireBrowserFlowConfig();

        MicrosoftTokens cached = store.load();
        if (cached != null) {
            if (!cached.expiresSoon() && !cached.accessToken().isBlank()) return cached;
            if (!cached.refreshToken().isBlank()) {
                try {
                    MicrosoftTokens refreshed = refresh(cached.refreshToken());
                    store.save(refreshed);
                    return refreshed;
                } catch (Exception e) {
                    System.out.println("[Auth] Cached refresh token was rejected; starting browser sign-in.");
                }
            }
        }

        MicrosoftTokens fresh = authorizationCodeFlow();
        store.save(fresh);
        return fresh;
    }

    private void requireBrowserFlowConfig() {
        if (config.clientId().isBlank()) {
            throw new IllegalStateException("Microsoft client ID is not configured.");
        }
        if (config.clientSecret().isBlank()) {
            throw new IllegalStateException("Microsoft client secret is not configured. Create one in Entra App registrations > NovaBroadcast > Certificates & secrets, then set microsoft.clientSecret.");
        }
        if (config.redirectUri().isBlank()) {
            throw new IllegalStateException("Microsoft redirect URI is not configured. Add a Web redirect URI to the Entra app and set the exact same value as microsoft.redirectUri.");
        }
        if (!"consumers".equalsIgnoreCase(config.tenant())) {
            System.out.println("[Auth] WARN Xbox website sign-in is documented against the consumers tenant; configured tenant is '" + config.tenant() + "'.");
        }
    }

    private MicrosoftTokens refresh(String refreshToken) throws Exception {
        String url = oauthBase() + "/token";
        Map<String,String> fields = new LinkedHashMap<>();
        fields.put("client_id", config.clientId());
        fields.put("client_secret", config.clientSecret());
        fields.put("scope", config.scope());
        fields.put("grant_type", "refresh_token");
        fields.put("refresh_token", refreshToken);
        fields.put("redirect_uri", config.redirectUri());

        Http.Response r = Http.post(url, Form.encode(fields),
                Map.of("Content-Type","application/x-www-form-urlencoded"));
        r.requireOk("Microsoft refresh");
        return tokenResponse(r.body(), refreshToken);
    }

    private MicrosoftTokens authorizationCodeFlow() throws Exception {
        String state = randomState();
        String authorizeUrl = oauthBase() + "/authorize?" + query(Map.of(
                "client_id", config.clientId(),
                "response_type", "code",
                "redirect_uri", config.redirectUri(),
                "response_mode", "query",
                "scope", config.scope(),
                "state", state
        ));

        System.out.println("[Auth] Xbox browser authorization is required.");
        System.out.println("[Auth] Open this URL in a browser and sign in with the Microsoft/Xbox account:");
        System.out.println(authorizeUrl);
        System.out.println("[Auth] After Microsoft redirects you, paste the FULL redirected URL here (or paste only the code= value):");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String pasted = in.readLine();
        if (pasted == null || pasted.isBlank()) {
            throw new IllegalStateException("No Microsoft authorization response was entered.");
        }

        AuthorizationResponse ar = parseAuthorizationResponse(pasted.trim());
        if (!ar.state().isBlank() && !state.equals(ar.state())) {
            throw new IllegalStateException("Microsoft authorization state mismatch; restart sign-in and use the newly printed URL.");
        }
        if (!ar.error().isBlank()) {
            throw new IllegalStateException("Microsoft authorization failed: " + ar.error() +
                    (ar.errorDescription().isBlank() ? "" : " - " + ar.errorDescription()));
        }
        if (ar.code().isBlank()) {
            throw new IllegalStateException("The pasted Microsoft response did not contain an authorization code.");
        }

        Map<String,String> fields = new LinkedHashMap<>();
        fields.put("client_id", config.clientId());
        fields.put("client_secret", config.clientSecret());
        fields.put("code", ar.code());
        fields.put("redirect_uri", config.redirectUri());
        fields.put("grant_type", "authorization_code");
        fields.put("scope", config.scope());

        Http.Response tr = Http.post(oauthBase() + "/token", Form.encode(fields),
                Map.of("Content-Type","application/x-www-form-urlencoded"));
        if (!tr.ok()) {
            String error = Json.string(tr.body(), "error");
            String desc = Json.string(tr.body(), "error_description");
            throw new IllegalStateException("Microsoft authorization-code exchange failed: HTTP " + tr.status() +
                    (error.isBlank() ? "" : " - " + error) + (desc.isBlank() ? "" : " - " + desc));
        }
        return tokenResponse(tr.body(), "");
    }

    private String oauthBase() {
        return "https://login.microsoftonline.com/" + config.tenant() + "/oauth2/v2.0";
    }

    private MicrosoftTokens tokenResponse(String body, String previousRefreshToken) {
        String access = Json.string(body, "access_token");
        String refresh = Json.string(body, "refresh_token");
        int expires = intField(body, "expires_in", 3600);
        if (access.isBlank()) throw new IllegalStateException("Microsoft token response did not include access_token.");
        if (refresh.isBlank()) refresh = previousRefreshToken == null ? "" : previousRefreshToken;
        return new MicrosoftTokens(access, refresh, System.currentTimeMillis()/1000L + expires);
    }

    private static AuthorizationResponse parseAuthorizationResponse(String pasted) {
        if (!pasted.contains("://") && !pasted.contains("?") && !pasted.contains("&")) {
            return new AuthorizationResponse(pasted, "", "", "");
        }
        String query = pasted;
        try {
            URI uri = URI.create(pasted);
            if (uri.getRawQuery() != null) query = uri.getRawQuery();
        } catch (Exception ignored) {
            int q = pasted.indexOf('?');
            if (q >= 0) query = pasted.substring(q + 1);
        }
        Map<String,String> values = parseQuery(query);
        return new AuthorizationResponse(
                values.getOrDefault("code", ""),
                values.getOrDefault("state", ""),
                values.getOrDefault("error", ""),
                values.getOrDefault("error_description", ""));
    }

    private static Map<String,String> parseQuery(String query) {
        Map<String,String> out = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(urlDecode(k), urlDecode(v));
        }
        return out;
    }

    private static String query(Map<String,String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String,String> e : fields.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            out.append('=');
            out.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String randomState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    private record AuthorizationResponse(String code, String state, String error, String errorDescription) {}
}
