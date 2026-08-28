package uk.blazecraft.novabroadcast;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates the authenticated client half of the NetherNet RFC 8827 identity.
 *
 * The GameServerToken trust anchor is supplied explicitly as a JWKS URI. This
 * class does not guess a Minecraft auth endpoint. Supported token algorithms
 * are RS256 and ES384; the cpk/fingerprint possession proof is ES384/P-384.
 */
final class ClientIdentityVerifier {
    private static final Base64.Decoder B64 = Base64.getDecoder();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final long JWKS_CACHE_SECONDS = 900;

    private final String jwksUrl;
    private volatile CachedKeys cachedKeys;

    ClientIdentityVerifier(String jwksUrl) {
        this.jwksUrl = Objects.requireNonNull(jwksUrl, "jwksUrl").trim();
        if (this.jwksUrl.isBlank()) {
            throw new IllegalArgumentException("nethernet.clientJwksUrl is required for authenticated client validation");
        }
    }

    VerifiedClient verify(String offerSdp, String encodedIdentity) throws Exception {
        if (encodedIdentity == null || encodedIdentity.isBlank()) {
            throw new SecurityException("Missing client a=identity assertion");
        }

        Envelope envelope = parseEnvelope(encodedIdentity);
        Jwt token = parseJwt(envelope.token());
        PublicKey issuerKey = resolveIssuerKey(token);
        verifyJwtSignature(token, issuerKey);
        validateTimes(token.claims());

        String cpk = stringClaim(token.claims(), "cpk");
        if (cpk.isBlank()) throw new SecurityException("GameServerToken is missing cpk");
        PublicKey clientKey = decodeCpk(cpk);
        verifyFingerprintJws(offerSdp, envelope.fingerprintJws(), clientKey);

        return new VerifiedClient(
                stringClaim(token.claims(), "XUID", "xuid"),
                stringClaim(token.claims(), "PlayFabId", "playFabId", "playfabid"),
                stringClaim(token.claims(), "UUID", "uuid", "identity"),
                envelope.idpDomain(),
                Map.copyOf(token.claims()));
    }

    private Envelope parseEnvelope(String encodedIdentity) {
        try {
            String envelopeJson = new String(B64.decode(encodedIdentity), StandardCharsets.UTF_8);
            Object root = Json.parse(envelopeJson);
            if (!(root instanceof Map<?,?> map)) throw new SecurityException("Client identity envelope is not an object");
            String protocol = nestedString(map, "idp", "protocol");
            if (!"default".equals(protocol)) throw new SecurityException("Unsupported client identity protocol");
            String domain = nestedString(map, "idp", "domain");
            String assertionJson = valueString(map.get("assertion"));
            Object assertionRoot = Json.parse(assertionJson);
            if (!(assertionRoot instanceof Map<?,?> assertion)) throw new SecurityException("Client assertion is not an object");
            String token = valueString(assertion.get("token"));
            String fingerprints = valueString(assertion.get("fingerprints"));
            if (token.isBlank() || fingerprints.isBlank()) throw new SecurityException("Client assertion is incomplete");
            return new Envelope(domain, token, fingerprints);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed client identity assertion", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Jwt parseJwt(String compact) {
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new SecurityException("Malformed GameServerToken JWT");
        }
        try {
            Object h = Json.parse(new String(B64URL.decode(parts[0]), StandardCharsets.UTF_8));
            Object c = Json.parse(new String(B64URL.decode(parts[1]), StandardCharsets.UTF_8));
            if (!(h instanceof Map<?,?> headerRaw) || !(c instanceof Map<?,?> claimsRaw)) {
                throw new SecurityException("Malformed GameServerToken JWT JSON");
            }
            Map<String,Object> header = new LinkedHashMap<>();
            headerRaw.forEach((k,v) -> header.put(String.valueOf(k), v));
            Map<String,Object> claims = new LinkedHashMap<>();
            claimsRaw.forEach((k,v) -> claims.put(String.valueOf(k), v));
            return new Jwt(parts[0], parts[1], parts[2], header, claims);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed GameServerToken JWT encoding", e);
        }
    }

    private PublicKey resolveIssuerKey(Jwt jwt) throws Exception {
        String alg = valueString(jwt.header().get("alg"));
        if (!alg.equals("RS256") && !alg.equals("ES384")) {
            throw new SecurityException("Unsupported GameServerToken alg: " + alg);
        }
        String kid = valueString(jwt.header().get("kid"));
        List<JwkKey> keys = keys();
        List<JwkKey> matching = keys.stream()
                .filter(k -> kid.isBlank() || kid.equals(k.kid()))
                .filter(k -> k.alg().isBlank() || alg.equals(k.alg()))
                .toList();
        if (matching.isEmpty()) {
            // Refresh once in case the issuer rotated keys after our cache fill.
            cachedKeys = null;
            matching = keys().stream()
                    .filter(k -> kid.isBlank() || kid.equals(k.kid()))
                    .filter(k -> k.alg().isBlank() || alg.equals(k.alg()))
                    .toList();
        }
        if (matching.size() != 1) {
            throw new SecurityException("Could not uniquely resolve GameServerToken signing key" +
                    (kid.isBlank() ? "" : " kid=" + kid));
        }
        return matching.get(0).key();
    }

    private List<JwkKey> keys() throws Exception {
        CachedKeys cache = cachedKeys;
        long now = Instant.now().getEpochSecond();
        if (cache != null && now - cache.loadedAt() < JWKS_CACHE_SECONDS) return cache.keys();
        synchronized (this) {
            cache = cachedKeys;
            if (cache != null && now - cache.loadedAt() < JWKS_CACHE_SECONDS) return cache.keys();
            Http.Response response = Http.get(jwksUrl, Map.of("Accept", "application/json"));
            response.requireOk("Minecraft client JWKS fetch");
            List<JwkKey> loaded = parseJwks(response.body());
            if (loaded.isEmpty()) throw new SecurityException("Minecraft client JWKS contains no usable keys");
            cachedKeys = new CachedKeys(now, List.copyOf(loaded));
            return cachedKeys.keys();
        }
    }

    private static List<JwkKey> parseJwks(String json) throws Exception {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?,?> map) || !(map.get("keys") instanceof List<?> list)) {
            throw new SecurityException("Invalid JWKS document");
        }
        List<JwkKey> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?,?> raw)) continue;
            String kty = valueString(raw.get("kty"));
            String kid = valueString(raw.get("kid"));
            String alg = valueString(raw.get("alg"));
            try {
                if ("RSA".equals(kty)) {
                    BigInteger n = new BigInteger(1, B64URL.decode(valueString(raw.get("n"))));
                    BigInteger e = new BigInteger(1, B64URL.decode(valueString(raw.get("e"))));
                    PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                    result.add(new JwkKey(kid, alg, key));
                } else if ("EC".equals(kty) && "P-384".equals(valueString(raw.get("crv")))) {
                    BigInteger x = new BigInteger(1, B64URL.decode(valueString(raw.get("x"))));
                    BigInteger y = new BigInteger(1, B64URL.decode(valueString(raw.get("y"))));
                    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                    parameters.init(new ECGenParameterSpec("secp384r1"));
                    ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
                    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
                    result.add(new JwkKey(kid, alg, key));
                }
            } catch (RuntimeException | GeneralSecurityException ignored) {
                // Ignore malformed/unsupported individual keys; fail if none remain.
            }
        }
        return result;
    }

    private static void verifyJwtSignature(Jwt jwt, PublicKey key) throws Exception {
        String alg = valueString(jwt.header().get("alg"));
        byte[] signature = B64URL.decode(jwt.signature());
        Signature verifier;
        if ("RS256".equals(alg)) {
            verifier = Signature.getInstance("SHA256withRSA");
        } else if ("ES384".equals(alg)) {
            verifier = Signature.getInstance("SHA384withECDSA");
            signature = joseToDer(signature);
        } else {
            throw new SecurityException("Unsupported GameServerToken alg: " + alg);
        }
        verifier.initVerify(key);
        verifier.update((jwt.encodedHeader() + "." + jwt.encodedClaims()).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(signature)) throw new SecurityException("Invalid GameServerToken signature");
    }

    private static void validateTimes(Map<String,Object> claims) {
        long now = Instant.now().getEpochSecond();
        Long exp = numberClaim(claims.get("exp"));
        Long nbf = numberClaim(claims.get("nbf"));
        if (exp != null && now - CLOCK_SKEW_SECONDS >= exp) throw new SecurityException("GameServerToken is expired");
        if (nbf != null && now + CLOCK_SKEW_SECONDS < nbf) throw new SecurityException("GameServerToken is not valid yet");
    }

    private static PublicKey decodeCpk(String encoded) throws Exception {
        byte[] der;
        try {
            der = B64.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("GameServerToken cpk is not base64", e);
        }
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new SecurityException("GameServerToken cpk must be P-384");
        }
        return key;
    }

    private static void verifyFingerprintJws(String offerSdp, String compact, PublicKey cpk) throws Exception {
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || !parts[1].isEmpty()) throw new SecurityException("Malformed detached fingerprint JWS");
        Object headerRoot = Json.parse(new String(B64URL.decode(parts[0]), StandardCharsets.UTF_8));
        if (!(headerRoot instanceof Map<?,?> header) || !"ES384".equals(valueString(header.get("alg")))) {
            throw new SecurityException("Fingerprint JWS must use ES384");
        }
        String canonical = canonicalFingerprints(offerSdp);
        String signingInput = parts[0] + "." + B64URL_ENC.encodeToString(canonical.getBytes(StandardCharsets.UTF_8));
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(cpk);
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(joseToDer(B64URL.decode(parts[2])))) {
            throw new SecurityException("Client identity fingerprint signature is invalid");
        }
    }

    private static String canonicalFingerprints(String sdp) {
        StringJoiner joiner = new StringJoiner(",", "{\"fingerprint\":[", "]}");
        int count = 0;
        for (String line : sdp.split("\\r?\\n")) {
            if (!line.startsWith("a=fingerprint:")) continue;
            String value = line.substring("a=fingerprint:".length()).trim();
            int space = value.indexOf(' ');
            if (space <= 0 || space == value.length() - 1) throw new SecurityException("Malformed SDP fingerprint");
            joiner.add("{\"algorithm\":" + Json.quote(value.substring(0, space)) +
                    ",\"digest\":" + Json.quote(value.substring(space + 1)) + "}");
            count++;
        }
        if (count == 0) throw new SecurityException("SDP offer contains no fingerprints");
        return joiner.toString();
    }

    private static byte[] joseToDer(byte[] jose) {
        if (jose.length != 96) throw new SecurityException("Invalid ES384 signature length");
        byte[] r = Arrays.copyOfRange(jose, 0, 48);
        byte[] s = Arrays.copyOfRange(jose, 48, 96);
        r = unsignedDerInteger(r);
        s = unsignedDerInteger(s);
        int bodyLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + bodyLength];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) bodyLength;
        der[i++] = 0x02;
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length); i += r.length;
        der[i++] = 0x02;
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }

    private static byte[] unsignedDerInteger(byte[] raw) {
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) start++;
        byte[] value = Arrays.copyOfRange(raw, start, raw.length);
        if ((value[0] & 0x80) != 0) {
            byte[] padded = new byte[value.length + 1];
            System.arraycopy(value, 0, padded, 1, value.length);
            return padded;
        }
        return value;
    }

    private static String nestedString(Map<?,?> map, String objectKey, String key) {
        Object nested = map.get(objectKey);
        if (!(nested instanceof Map<?,?> child)) return "";
        return valueString(child.get(key));
    }

    private static String valueString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringClaim(Map<String,Object> claims, String... names) {
        for (String name : names) {
            Object value = claims.get(name);
            if (value != null) return String.valueOf(value);
        }
        return "";
    }

    private static Long numberClaim(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    record VerifiedClient(String xuid, String playFabId, String uuid, String idpDomain, Map<String,Object> claims) {}
    private record Envelope(String idpDomain, String token, String fingerprintJws) {}
    private record Jwt(String encodedHeader, String encodedClaims, String signature,
                       Map<String,Object> header, Map<String,Object> claims) {}
    private record JwkKey(String kid, String alg, PublicKey key) {}
    private record CachedKeys(long loadedAt, List<JwkKey> keys) {}
}
