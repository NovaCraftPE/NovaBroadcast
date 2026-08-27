# NovaBroadcast Clean-Room v0.4

NovaBroadcast is an independent Java implementation and does **not** include,
download, compile, shade, or launch MCXboxBroadcast/Broadcaster source or JARs.

Current milestone:
- Java 21 standalone application
- Microsoft device-code sign-in and refresh-token cache
- Xbox User Token / XSTS exchange and profile lookup
- clean MPSD preflight and guarded session lifecycle
- NetherNet HTTP signaling (`GET /v1/join`, `POST /v1/join/{networkId}`)
- NetherNet reliable/unreliable one-byte framing and reliable fragmentation/reassembly
- native answering-side WebRTC via the general-purpose Apache-2.0 `webrtc-java` library
- headless ICE/DTLS/SCTP peer operation with incoming/outbound data channels
- persistent P-384 NetherNet operator identity
- client `a=identity` stripping before native WebRTC SDP parsing
- Minecraft OpenID discovery for current client signing keys
- GameServerToken signature, issuer, audience, expiry/not-before, `cpk`, and detached fingerprint-proof validation
- self-signed ES384 server identity JWT plus detached JWS over DTLS fingerprints
- signed server `a=identity` insertion into every SDP answer
- fixed configurable ICE UDP port range for container/Pterodactyl deployments
- version-aware Bedrock `TransferPacket` encoder boundary
- conservative live Bedrock wire inspection for direct and exact-length-prefixed packet shapes
- Java 21 CI, protocol/identity self-tests, exact transfer-byte tests, and native WebRTC smoke test

v0.4 can create a real WebRTC answering peer, gather ICE, authenticate a client
assertion when present, sign the final answer with the server identity required by
Minecraft, and exchange framed binary application data over the two NetherNet
data channels. It still deliberately does **not** publish a Minecraft/Xbox
joinable MPSD session until the remaining Minecraft-specific session metadata and
Bedrock connection-state handling are established.

## Build and test

Requires Java 21 and Maven:

    ./build.sh
    java -jar NovaBroadcast.jar --self-test
    java -jar NovaBroadcast.jar --webrtc-smoke-test

Output:

    NovaBroadcast.jar

The build uses `dev.onvoid.webrtc:webrtc-java:0.14.0`, which supplies the
platform WebRTC JNI implementation. GitHub Actions verifies normal protocol
logic, identity persistence/signing, and that the native WebRTC factory loads
from the packaged JAR.

On Debian/Ubuntu Linux, the JNI library is dynamically linked to PulseAudio even
though NovaBroadcast uses WebRTC's dummy audio layer. Install the small runtime
library; no PulseAudio daemon or real audio device is required:

    apt-get update && apt-get install -y --no-install-recommends libpulse0

## Run

    java -jar NovaBroadcast.jar

On first launch `config.properties` is created. Set `microsoft.clientId` to an
application/client ID authorized for the Xbox Live scopes used by the account
flow. Tokens are cached in `data/auth.properties`.

Example transport configuration:

    nethernet.enabled=true
    nethernet.listenHost=0.0.0.0
    nethernet.listenPort=19134
    nethernet.maxSdpBytes=1048576
    nethernet.maxSctpMessageSize=262144
    nethernet.identityKey=data/nethernet-identity.key
    nethernet.identityDomain=self
    nethernet.requireClientIdentity=false
    nethernet.clientIssuer=https://authorization.franchise.minecraft-services.net/
    nethernet.clientAudience=api://auth-minecraft-services/multiplayer
    nethernet.clientJwksUrl=
    nethernet.stunUrl=stun:stun.l.google.com:19302
    nethernet.iceMinPort=20000
    nethernet.iceMaxPort=20100

The signaling TCP port and configured ICE UDP range must be reachable from
outside the container. On Pterodactyl, allocate/map the UDP range before testing
real Bedrock connections.

`nethernet.identityKey` is generated automatically on first use and must remain
private and stable. Minecraft's plaintext-signaling TOFU trust is attached to
this operator key, so deleting or replacing it makes the server appear as a new
operator to clients.

## Minecraft client authentication

NovaBroadcast uses the configured issuer's OpenID Connect discovery document:

    https://authorization.franchise.minecraft-services.net/.well-known/openid-configuration

The document supplies the expected issuer and current `jwks_uri`; the JWKS is
then cached by the lower-level verifier and refreshed when a token references an
unknown key ID. `nethernet.clientJwksUrl` is only an override for alternate/test
environments and normally stays blank.

For a client `a=identity`, NovaBroadcast verifies:
1. the envelope and `idp.protocol=default`,
2. the GameServerToken signature using the discovered signing key,
3. `exp` and `nbf`,
4. the token `iss` against the discovered issuer,
5. the multiplayer `aud`,
6. that the SDP identity-provider domain matches the token issuer,
7. the P-384 `cpk` embedded in the token, and
8. the detached ES384 signature over the offer's DTLS fingerprints.

Authentication occurs before any ICE/DTLS/SCTP peer is allocated. Invalid
assertions are rejected at signaling time with HTTP 403. With
`nethernet.requireClientIdentity=false`, identityless offers may still be used
for development; assertions that are present are always verified. Set it to
`true` for authenticated-only operation.

When an authenticated SDP offer is accepted, NovaBroadcast:
1. removes the client's session-level `a=identity` before native WebRTC parsing,
2. creates a headless native WebRTC peer and applies the cleaned offer,
3. creates/sets an answer and waits for ICE gathering,
4. self-signs a server JWT containing the long-lived public `cpk`,
5. signs the answer's DTLS fingerprints with a detached ES384 JWS,
6. inserts the resulting `a=identity` before the first media section,
7. returns the signed SDP answer,
8. accepts `ReliableDataChannel` / `UnreliableDataChannel`, and
9. reassembles/inspects recovered application payloads without mutating them.

Outbound application payloads can also be sent through the same channels.
Reliable payloads are fragmented according to NetherNet countdown framing;
oversized unreliable payloads are dropped instead of fragmented.

## Bedrock transfer boundary

NovaBroadcast contains a small independent `TransferPacket` encoder. For current
protocols it encodes packet ID 85, a VarUInt-length UTF-8 server address, a
little-endian uint16 port, the reload-world flag, and the optional gatherings
presence marker introduced at protocol 2168. The exact byte layout is covered by
`--self-test`.

The encoder is intentionally **not auto-injected yet**. A correct packet still
has to be sent at the correct Bedrock connection state and with whatever packet
length, compression, or encryption envelope the active client session expects.

Incoming application payloads are inspected conservatively. NovaBroadcast logs
a packet ID/sub-client header only when the bytes structurally match either a
direct Bedrock packet or an exact VarUInt-length-prefixed packet. Unknown or
enveloped payloads remain untouched and are reported as such.

## MPSD safety

`session.scid` and `session.template` must be explicit title-authorized values.
NovaBroadcast does not embed guessed Minecraft Retail identifiers.

`session.writeEnabled=false` remains the safe default. The project continues to
refuse to publish an unreachable or incorrectly-described Xbox session while the
remaining Minecraft-specific session work is unfinished.

## Source provenance

All NovaBroadcast Java sources were written for this project. The WebRTC engine
is consumed as an ordinary general-purpose Apache-2.0 Maven dependency; no
broadcaster-specific implementation is copied or used at runtime. Public service
endpoint names, signaling shapes, identity rules, and framing are based on
public Microsoft/Xbox and Mojang Bedrock documentation. Independent protocol
libraries/specifications are used only as interoperability cross-checks where
public Mojang documentation does not specify an inner transport detail.
