package uk.blazecraft.novabroadcast;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

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
            throw new IllegalStateException("Microsoft redirect URI is not configured. Register a public HTTPS Web redirect URI on the Entra app and set the exact same value as microsoft.redirectUri.");
        }
        URI redirect = URI.create(config.redirectUri());
        if (redirect.getPath() == null || redirect.getPath().isBlank() || "/".equals(redirect.getPath())) {
            throw new IllegalStateException("microsoft.redirectUri must include a callback path, for example https://auth.example.com/microsoft/callback");
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

        URI redirect = URI.create(config.redirectUri());
        String callbackPath = redirect.getPath();
        ArrayBlockingQueue<AuthorizationResponse> responses = new ArrayBlockingQueue<>(1);
        HttpServer callbackServer = HttpServer.create(
                new InetSocketAddress(config.microsoftCallbackListenHost(), config.microsoftCallbackListenPort()), 0);
        callbackServer.createContext(callbackPath, exchange -> handleCallback(exchange, responses));
        callbackServer.setExecutor(null);
        callbackServer.start();

        try {
            System.out.println("[Auth] Microsoft callback listener is ready on " +
                    config.microsoftCallbackListenHost() + ":" + config.microsoftCallbackListenPort() + callbackPath);
            System.out.println("[Auth] Public redirect URI: " + config.redirectUri());
            System.out.println("[Auth] Open this URL in a browser and sign in with the Microsoft/Xbox account:");
            System.out.println(authorizeUrl);
            System.out.println("[Auth] Waiting up to " + config.microsoftCallbackTimeoutSeconds() + " seconds for Microsoft to redirect back automatically...");

            AuthorizationResponse ar = responses.poll(config.microsoftCallbackTimeoutSeconds(), TimeUnit.SECONDS);
            if (ar == null) {
                throw new IllegalStateException("Timed out waiting for the Microsoft callback. Verify the public redirect URI reaches this server and is registered exactly in Entra.");
            }
            if (!ar.state().isBlank() && !state.equals(ar.state())) {
                throw new IllegalStateException("Microsoft authorization state mismatch; restart sign-in and use the newly printed URL.");
            }
            if (!ar.error().isBlank()) {
                throw new IllegalStateException("Microsoft authorization failed: " + ar.error() +
                        (ar.errorDescription().isBlank() ? "" : " - " + ar.errorDescription()));
            }
            if (ar.code().isBlank()) {
                throw new IllegalStateException("The Microsoft callback did not contain an authorization code.");
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
            System.out.println("[Auth] Microsoft authorization callback received successfully.");
            return tokenResponse(tr.body(), "");
        } finally {
            callbackServer.stop(0);
        }
    }

    private static void handleCallback(HttpExchange exchange, ArrayBlockingQueue<AuthorizationResponse> responses) throws IOException {
        AuthorizationResponse ar;
        try {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            Map<String,String> values = parseQuery(rawQuery == null ? "" : rawQuery);
            ar = new AuthorizationResponse(
                    values.getOrDefault("code", ""),
                    values.getOrDefault("state", ""),
                    values.getOrDefault("error", ""),
                    values.getOrDefault("error_description", ""));
        } catch (Exception e) {
            ar = new AuthorizationResponse("", "", "invalid_callback", e.getMessage() == null ? "Invalid callback" : e.getMessage());
        }

        responses.offer(ar);
        String body = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>NovaBroadcast</title></head>
<body style="font-family:system-ui;padding:32px;max-width:680px;margin:auto"><h1>NovaBroadcast</h1><p>Microsoft sign-in has returned to the server. You can close this page and go back to the server console.</p></body></html>
""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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

    private static Map<String,String> parseQuery(String query) {
        Map<String,String> out = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return out;
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
