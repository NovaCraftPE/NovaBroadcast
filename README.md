# NovaBroadcast Clean-Room v0.5

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
- Minecraft OpenID discovery and GameServerToken/fingerprint-proof validation before WebRTC allocation
- signed server `a=identity` insertion into every SDP answer
- fixed configurable ICE UDP port range for container/Pterodactyl deployments
- protocol-2168 Bedrock batch framing with the pre/post-NetworkSettings boundary
- guarded redirect-only Bedrock login/resource-pack bootstrap
- version-aware `TransferPacket` encoder and automatic transfer at the completed resource-pack boundary
- Java 21 CI, protocol/identity/redirect self-tests, exact packet-byte tests, and native WebRTC smoke test

v0.5 can create a real WebRTC answering peer, authenticate/sign the NetherNet
identity exchange, accept Bedrock application traffic, negotiate Bedrock network
settings using `Compression::None`, perform the minimum unencrypted login and
empty resource-pack exchange, then send `TransferPacket` instead of constructing
a world. The redirect flow is **disabled by default** and currently restricted
to explicitly mapped protocol-2168 game versions.

It still deliberately does **not** publish a Minecraft/Xbox joinable MPSD session
until the remaining Minecraft-specific session-advertisement metadata is known
and verified. The clean-room project does not guess Retail SCIDs/templates.

## Build and test

Requires Java 21 and Maven:

    ./build.sh
    java -jar NovaBroadcast.jar --self-test
    java -jar NovaBroadcast.jar --webrtc-smoke-test

Output:

    NovaBroadcast.jar

The build uses `dev.onvoid.webrtc:webrtc-java:0.14.0`. GitHub Actions verifies
normal protocol logic, redirect guards, identity persistence/signing, and native
WebRTC initialization from the packaged JAR.

On Debian/Ubuntu Linux the JNI library requires the PulseAudio runtime library,
even though NovaBroadcast uses WebRTC's dummy audio device:

    apt-get update && apt-get install -y --no-install-recommends libpulse0

## Run

    java -jar NovaBroadcast.jar

On first launch `config.properties` is created. Set `microsoft.clientId` to an
application/client ID authorized for the Xbox Live scopes used by the account
flow. Tokens are cached in `data/auth.properties`.

Example transport/redirect configuration:

    target.host=54.37.245.44
    target.port=19133
    target.name=NovaCraft

    bedrock.redirectEnabled=false
    bedrock.gameVersion=1.26.44

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

The signaling TCP port and configured ICE UDP range must be reachable externally.
On Pterodactyl, allocate/map the UDP range before testing real Bedrock clients.

`nethernet.identityKey` is generated automatically on first use and must remain
private and stable. Minecraft's plaintext-signaling TOFU trust is attached to
this operator key.

## Minecraft client authentication

NovaBroadcast uses the configured issuer's OpenID Connect discovery document:

    https://authorization.franchise.minecraft-services.net/.well-known/openid-configuration

The discovery document supplies the issuer and current `jwks_uri`. For a client
`a=identity`, NovaBroadcast validates the GameServerToken signature and time
claims, multiplayer audience, P-384 `cpk`, IdP domain, and detached ES384 proof
over the offer's DTLS fingerprints. Invalid assertions are rejected with HTTP
403 before ICE/DTLS/SCTP state is allocated.

`nethernet.requireClientIdentity=false` permits identityless development offers;
any assertion that is present is still verified. Set it to `true` for
authenticated-only admission.

## Bedrock redirect bootstrap

The redirect implementation is intentionally small rather than a fake Bedrock
world server. For a supported protocol-2168 client it performs:

1. client `RequestNetworkSettings` (packet 193),
2. server `NetworkSettings` (143) using compression algorithm `NONE`,
3. client `Login` (1),
4. server `PlayStatus(LoginSuccess)` (2) plus empty `ResourcePacksInfo` (6),
5. client resource-pack response `HAVE_ALL_PACKS`,
6. server empty `ResourcePackStack` (7),
7. client resource-pack response `COMPLETED`, and
8. server `TransferPacket` (85) to `target.host:target.port`.

Bedrock's encryption handshake is not enabled by this redirect-only bootstrap;
encryption is optional in the normal login flow, so the bridge can reach the
transfer boundary without inventing a world, chunks, entity state, or StartGame
payload.

`NetworkSettings` and the later game batches are distinct wire phases. The
NetworkSettings algorithm field uses enum value 2 for `NONE`; after negotiation,
the per-batch method prefix for `NONE` is `0xFF`. NovaBroadcast models and tests
that boundary explicitly, including optional `0xFE` game-packet markers and
VarUInt packet lengths.

`bedrock.redirectEnabled=false` is the safe default and emits no Bedrock handshake
responses. When enabled, the configured game version must map to a tested
protocol; currently the 1.26.40/1.26.43/1.26.44 family maps to protocol 2168.
A mismatched client gets no fabricated compatibility response.

## MPSD safety

`session.scid` and `session.template` must be explicit title-authorized values.
NovaBroadcast does not embed guessed Minecraft Retail identifiers.

`session.writeEnabled=false` remains the safe default. Even though the transport
and redirect bootstrap now exist, the project still refuses to publish a
joinable Xbox session until the title-specific MPSD discovery properties are
verified from legitimate configuration/documentation.

## Source provenance

All NovaBroadcast Java sources were written for this project. The WebRTC engine
is consumed as an ordinary general-purpose Apache-2.0 Maven dependency; no
broadcaster-specific implementation is copied or used at runtime. Public service
endpoint names, signaling shapes, identity rules, and Bedrock packet layouts are
based on public Microsoft/Xbox and Mojang documentation. Independent protocol
libraries/specifications are used only as interoperability cross-checks where
public Mojang documentation does not specify an inner transport detail.
