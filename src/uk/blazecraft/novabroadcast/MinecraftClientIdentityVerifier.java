package uk.blazecraft.novabroadcast;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Production Minecraft client-identity trust bootstrap.
 *
 * Rather than pinning a static JWKS URL, this class uses the authorization
 * service's OpenID Connect discovery document and then delegates signature and
 * DTLS-fingerprint proof verification to ClientIdentityVerifier.
 */
final class MinecraftClientIdentityVerifier {
    static final String DEFAULT_ISSUER = "https://authorization.franchise.minecraft-services.net/";
    static final String DEFAULT_AUDIENCE = "api://auth-minecraft-services/multiplayer";

    private final String configuredIssuer;
    private final String expectedAudience;
    private final String jwksOverride;
    private volatile Discovery discovery;
    private volatile ClientIdentityVerifier verifier;

    MinecraftClientIdentityVerifier(String issuer, String audience, String jwksOverride) {
        this.configuredIssuer = normalizeIssuer(issuer == null || issuer.isBlank() ? DEFAULT_ISSUER : issuer);
        this.expectedAudience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience.trim();
        this.jwksOverride = jwksOverride == null ? "" : jwksOverride.trim();
        requireHttps(this.configuredIssuer, "Minecraft client issuer");
        if (!this.jwksOverride.isBlank()) requireHttps(this.jwksOverride, "Minecraft client JWKS override");
    }

    ClientIdentityVerifier.VerifiedClient verify(String offerSdp, String encodedIdentity) throws Exception {
        Trust trust = trust();
        ClientIdentityVerifier.VerifiedClient client = trust.verifier().verify(offerSdp, encodedIdentity);
        Map<String,Object> claims = client.claims();

        String tokenIssuer = string(claims.get("iss"));
        if (!normalizeIssuer(tokenIssuer).equals(normalizeIssuer(trust.discovery().issuer()))) {
            throw new SecurityException("GameServerToken issuer mismatch");
        }
        if (!audienceContains(claims.get("aud"), expectedAudience)) {
            throw new SecurityException("GameServerToken audience mismatch");
        }
        if (!client.idpDomain().isBlank()) {
            String idp = normalizeIssuer(client.idpDomain());
            String discovered = normalizeIssuer(trust.discovery().issuer());
            if (!idp.equals(discovered)) {
                throw new SecurityException("Client identity provider does not match token issuer");
            }
        }
        return client;
    }

    String issuer() throws Exception { return trust().discovery().issuer(); }
    String jwksUrl() throws Exception { return trust().discovery().jwksUri(); }

    private Trust trust() throws Exception {
        ClientIdentityVerifier current = verifier;
        Discovery currentDiscovery = discovery;
        if (current != null && currentDiscovery != null) return new Trust(currentDiscovery, current);

        synchronized (this) {
            if (verifier != null && discovery != null) return new Trust(discovery, verifier);

            Discovery loaded = discover();
            String jwks = jwksOverride.isBlank() ? loaded.jwksUri() : jwksOverride;
            requireHttps(jwks, "Minecraft client JWKS URI");
            discovery = new Discovery(loaded.issuer(), jwks);
            verifier = new ClientIdentityVerifier(jwks);
            return new Trust(discovery, verifier);
        }
    }

    private Discovery discover() throws Exception {
        String discoveryUrl = configuredIssuer + ".well-known/openid-configuration";
        Http.Response response = Http.get(discoveryUrl, Map.of("Accept", "application/json"));
        response.requireOk("Minecraft OpenID discovery");

        Object root = Json.parse(response.body());
        if (!(root instanceof Map<?,?> map)) throw new SecurityException("Invalid Minecraft OpenID discovery document");
        String issuer = string(map.get("issuer"));
        String jwks = string(map.get("jwks_uri"));
        if (issuer.isBlank() || jwks.isBlank()) throw new SecurityException("Minecraft OpenID discovery is missing issuer/jwks_uri");
        if (!normalizeIssuer(issuer).equals(configuredIssuer)) {
            throw new SecurityException("Minecraft OpenID discovery issuer mismatch");
        }
        requireHttps(jwks, "Minecraft OpenID jwks_uri");
        return new Discovery(issuer, jwks);
    }

    private static boolean audienceContains(Object value, String expected) {
        if (value instanceof String s) return expected.equals(s);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (expected.equals(String.valueOf(item))) return true;
        }
        return false;
    }

    private static String normalizeIssuer(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank()) return "";
        return v.endsWith("/") ? v : v + "/";
    }

    private static void requireHttps(String value, String label) {
        URI uri = URI.create(Objects.requireNonNull(value));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(label + " must be an absolute HTTPS URL");
        }
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }

    private record Discovery(String issuer, String jwksUri) {}
    private record Trust(Discovery discovery, ClientIdentityVerifier verifier) {}
}
