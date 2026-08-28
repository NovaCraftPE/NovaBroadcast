package uk.blazecraft.novabroadcast;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;

/**
 * Server-side RFC 8827/NetherNet identity helper.
 *
 * The long-lived operator key is P-384. The server identity JWT is self-signed
 * with ES384 and carries the public key in the cpk claim. The same key signs a
 * detached JWS over the canonical DTLS fingerprint JSON.
 */
final class NetherNetIdentity {
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private final KeyPair keyPair;
    private final String domain;

    private NetherNetIdentity(KeyPair keyPair, String domain) {
        this.keyPair = keyPair;
        this.domain = domain == null || domain.isBlank() ? "self" : domain;
    }

    static NetherNetIdentity loadOrCreate(Path privateKeyPath, String domain) throws Exception {
        Objects.requireNonNull(privateKeyPath, "privateKeyPath");
        KeyPair pair;
        if (Files.exists(privateKeyPath)) {
            byte[] encoded = Base64.getDecoder().decode(Files.readString(privateKeyPath).trim());
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (!(privateKey instanceof ECPrivateKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 384) {
                throw new IllegalStateException("NetherNet identity key must be EC P-384");
            }
            PublicKey publicKey = derivePublicKey(ec);
            pair = new KeyPair(publicKey, privateKey);
        } else {
            Path parent = privateKeyPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            pair = generator.generateKeyPair();
            Files.writeString(privateKeyPath, B64.encodeToString(pair.getPrivate().getEncoded()) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        return new NetherNetIdentity(pair, domain);
    }

    static Offer stripClientIdentity(String offerSdp) {
        StringBuilder clean = new StringBuilder();
        String encoded = "";
        for (String line : offerSdp.split("\\r?\\n", -1)) {
            if (line.startsWith("a=identity:")) {
                if (encoded.isEmpty()) encoded = line.substring("a=identity:".length()).trim();
                continue;
            }
            clean.append(line).append("\r\n");
        }
        return new Offer(clean.toString(), encoded);
    }

    String signAnswer(String answerSdp) throws Exception {
        List<Fingerprint> fingerprints = fingerprints(answerSdp);
        if (fingerprints.isEmpty()) throw new IllegalStateException("SDP answer contains no DTLS fingerprint");

        String fingerprintJson = canonicalFingerprints(fingerprints);
        String fingerprintJws = detachedJws(fingerprintJson.getBytes(StandardCharsets.UTF_8));
        String token = serverToken();
        String assertion = "{" +
                "\"fingerprints\":" + Json.quote(fingerprintJws) + "," +
                "\"token\":" + Json.quote(token) + "}";
        String envelope = "{" +
                "\"idp\":{" +
                    "\"domain\":" + Json.quote(domain) + "," +
                    "\"protocol\":\"default\"}," +
                "\"assertion\":" + Json.quote(assertion) + "}";
        String identityLine = "a=identity:" + B64.encodeToString(envelope.getBytes(StandardCharsets.UTF_8));

        StringBuilder result = new StringBuilder();
        boolean inserted = false;
        for (String line : answerSdp.split("\\r?\\n", -1)) {
            if (!inserted && line.startsWith("m=")) {
                result.append(identityLine).append("\r\n");
                inserted = true;
            }
            if (!line.isEmpty()) result.append(line).append("\r\n");
        }
        if (!inserted) throw new IllegalStateException("SDP answer contains no media section");
        return result.toString();
    }

    String publicKeyFingerprint() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyPair.getPublic().getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    private String serverToken() throws Exception {
        long now = Instant.now().getEpochSecond();
        long exp = now + 3600;
        String publicKey = B64.encodeToString(keyPair.getPublic().getEncoded());
        String header = "{\"alg\":\"ES384\",\"typ\":\"JWT\"}";
        String claims = "{" +
                "\"cpk\":" + Json.quote(publicKey) + "," +
                "\"exp\":" + exp + "," +
                "\"iat\":" + now + "," +
                "\"iss\":" + Json.quote(domain) + "}";
        String signingInput = url(header.getBytes(StandardCharsets.UTF_8)) + "." +
                url(claims.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + url(sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private String detachedJws(byte[] payload) throws Exception {
        String header = "{\"alg\":\"ES384\"}";
        String encodedHeader = url(header.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + url(payload);
        return encodedHeader + ".." + url(sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private byte[] sign(byte[] input) throws Exception {
        Signature signature = Signature.getInstance("SHA384withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input);
        return derToJose(signature.sign(), 48);
    }

    private static String canonicalFingerprints(List<Fingerprint> fingerprints) {
        StringJoiner join = new StringJoiner(",", "{\"fingerprint\":[", "]}");
        for (Fingerprint fingerprint : fingerprints) {
            join.add("{\"algorithm\":" + Json.quote(fingerprint.algorithm()) +
                    ",\"digest\":" + Json.quote(fingerprint.digest()) + "}");
        }
        return join.toString();
    }

    private static List<Fingerprint> fingerprints(String sdp) {
        List<Fingerprint> values = new ArrayList<>();
        for (String line : sdp.split("\\r?\\n")) {
            if (!line.startsWith("a=fingerprint:")) continue;
            String value = line.substring("a=fingerprint:".length()).trim();
            int space = value.indexOf(' ');
            if (space <= 0 || space == value.length() - 1) continue;
            values.add(new Fingerprint(value.substring(0, space), value.substring(space + 1)));
        }
        return values;
    }

    private static String url(byte[] value) {
        return B64URL.encodeToString(value);
    }

    private static byte[] derToJose(byte[] der, int fieldSize) {
        int index = 0;
        if ((der[index++] & 0xff) != 0x30) throw new IllegalArgumentException("Invalid ECDSA DER signature");
        int seqLength = readDerLength(der, index);
        index += derLengthBytes(der[index]);
        if (seqLength <= 0 || index >= der.length || (der[index++] & 0xff) != 0x02) {
            throw new IllegalArgumentException("Invalid ECDSA DER signature");
        }
        int rLength = readDerLength(der, index);
        index += derLengthBytes(der[index]);
        byte[] r = Arrays.copyOfRange(der, index, index + rLength);
        index += rLength;
        if (index >= der.length || (der[index++] & 0xff) != 0x02) throw new IllegalArgumentException("Invalid ECDSA DER signature");
        int sLength = readDerLength(der, index);
        index += derLengthBytes(der[index]);
        byte[] s = Arrays.copyOfRange(der, index, index + sLength);

        byte[] jose = new byte[fieldSize * 2];
        copyUnsigned(r, jose, 0, fieldSize);
        copyUnsigned(s, jose, fieldSize, fieldSize);
        return jose;
    }

    private static int readDerLength(byte[] der, int index) {
        int first = der[index] & 0xff;
        if ((first & 0x80) == 0) return first;
        int count = first & 0x7f;
        int length = 0;
        for (int i = 1; i <= count; i++) length = (length << 8) | (der[index + i] & 0xff);
        return length;
    }

    private static int derLengthBytes(byte first) {
        int value = first & 0xff;
        return (value & 0x80) == 0 ? 1 : 1 + (value & 0x7f);
    }

    private static void copyUnsigned(byte[] integer, byte[] output, int offset, int size) {
        int start = 0;
        while (start < integer.length - 1 && integer[start] == 0) start++;
        int length = integer.length - start;
        if (length > size) throw new IllegalArgumentException("ECDSA integer too large");
        System.arraycopy(integer, start, output, offset + size - length, length);
    }

    private static PublicKey derivePublicKey(ECPrivateKey privateKey) throws Exception {
        // JCA does not expose generic EC point multiplication. Reconstruct the
        // public key using the named P-384 parameters and a small affine scalar
        // multiplication implementation, run only when loading the persistent key.
        ECParameterSpec params = privateKey.getParams();
        ECPoint point = multiply(params.getGenerator(), privateKey.getS(), params);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, params));
    }

    private static ECPoint multiply(ECPoint p, BigInteger k, ECParameterSpec params) {
        ECPoint result = null;
        ECPoint addend = p;
        BigInteger modulus = ((ECFieldFp) params.getCurve().getField()).getP();
        BigInteger a = params.getCurve().getA();
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) result = add(result, addend, modulus, a);
            addend = add(addend, addend, modulus, a);
        }
        if (result == null) throw new IllegalStateException("Invalid EC private key");
        return result;
    }

    private static ECPoint add(ECPoint p, ECPoint q, BigInteger mod, BigInteger a) {
        if (p == null) return q;
        if (q == null) return p;
        BigInteger x1 = p.getAffineX(), y1 = p.getAffineY();
        BigInteger x2 = q.getAffineX(), y2 = q.getAffineY();
        if (x1.equals(x2) && y1.add(y2).mod(mod).equals(BigInteger.ZERO)) return null;
        BigInteger slope;
        if (p.equals(q)) {
            slope = x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(a)
                    .multiply(y1.multiply(BigInteger.TWO).modInverse(mod)).mod(mod);
        } else {
            slope = y2.subtract(y1).multiply(x2.subtract(x1).mod(mod).modInverse(mod)).mod(mod);
        }
        BigInteger x3 = slope.multiply(slope).subtract(x1).subtract(x2).mod(mod);
        BigInteger y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(mod);
        return new ECPoint(x3, y3);
    }

    record Offer(String cleanSdp, String encodedIdentity) {
        boolean hasIdentity() { return !encodedIdentity.isBlank(); }
    }
    private record Fingerprint(String algorithm, String digest) {}

    private NetherNetIdentity() { throw new AssertionError(); }
}
