# NovaBroadcast Clean-Room v0.5

NovaBroadcast is an independent Java implementation and does **not** include,
download, compile, shade, or launch MCXboxBroadcast/Broadcaster source or JARs.

Current milestone:
- Java 21 standalone application
- Microsoft device-code sign-in and refresh-token cache
- Xbox User Token / XSTS exchange and profile lookup
- clean MPSD preflight and guarded session lifecycle
- authenticated MPSD activity discovery and activity-handle binding
- NetherNet HTTP signaling (`GET /v1/join`, `POST /v1/join/{networkId}`)
- native answering-side WebRTC via Apache-2.0 `webrtc-java`
- persistent P-384 NetherNet operator identity and signed server `a=identity`
- Minecraft client GameServerToken/fingerprint-proof verification through OpenID discovery
- protocol-2168 Bedrock batch framing and redirect-only handshake
- guarded automatic `TransferPacket` to the configured target after resource-pack completion
- explicit MPSD custom-property file support without guessed Minecraft-owned keys
- Java 21 CI, protocol/identity/redirect self-tests, native WebRTC smoke test, and tested JAR artifact

## Build and test

    ./build.sh
    java -jar NovaBroadcast.jar --self-test
    java -jar NovaBroadcast.jar --webrtc-smoke-test

Output:

    NovaBroadcast.jar

On Debian/Ubuntu Linux install the WebRTC JNI runtime dependency:

    apt-get update && apt-get install -y --no-install-recommends libpulse0

## Run

    java -jar NovaBroadcast.jar

On first launch `config.properties` is created. Set `microsoft.clientId` to an
application/client ID authorized for the Xbox Live scopes used by the account
flow. Tokens are cached in `data/auth.properties`.

## Discover your own Xbox activities

NovaBroadcast can query the authenticated account's own MPSD activity handles
using the public `/handles/query` contract without creating or modifying a
session:

    java -jar NovaBroadcast.jar --dump-activities

The raw response is stored at:

    data/mpsd-activities.json

NovaBroadcast also prints each returned `titleId`, SCID, template name and
session name. The query asks MPSD for `relatedInfo,session` and uses the signed-in
account's XUID filter, including private/inactive/reservation results that the
caller is authorized to inspect. This mode is intended to capture legitimate
session metadata from the user's own Xbox activity rather than hard-code or copy
Minecraft-owned constants.

A useful clean-room test is to start/join a normal Minecraft Bedrock multiplayer
session on the same Microsoft/Xbox account, then run `--dump-activities` and
inspect the resulting session reference/data.

## Bedrock redirect bootstrap

The redirect path is intentionally narrow rather than a general Bedrock server.
It currently targets the explicitly tested protocol-2168 family:

- 1.26.40
- 1.26.43
- 1.26.44

Example:

    target.host=54.37.245.44
    target.port=19133
    target.name=NovaCraft
    bedrock.redirectEnabled=false
    bedrock.gameVersion=1.26.44

When enabled, the redirect state machine performs:

    RequestNetworkSettings
      -> NetworkSettings (compression enum NONE / 2)
      -> Login
      -> PlayStatus(LoginSuccess) + empty ResourcePacksInfo
      -> empty ResourcePackStack
      -> TransferPacket

Post-NetworkSettings no-compression batches use the Bedrock method prefix
`0xFF`. NovaBroadcast skips the optional Bedrock encryption handshake and does
not fabricate `StartGame`, chunks, entities, or a fake world. Mismatched protocol
versions receive no invented compatibility response.

## NetherNet

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

The signaling TCP port and configured ICE UDP range must be externally reachable.

## MPSD publication

MPSD session templates are title-owned resources configured through Xbox Partner
Center. NovaBroadcast requires explicit authorized values:

    session.enabled=false
    session.scid=
    session.template=
    session.name=NovaBroadcast
    session.writeEnabled=false
    session.setActivity=false

Microsoft MPSD templates control system constants such as visibility and
connectivity capabilities. Minecraft-specific bootstrap values live in custom
title properties and are not publicly standardized by MPSD. NovaBroadcast does
not guess those private Minecraft property names.

When legitimate title/session integration data is available, put the exact
custom JSON objects into files and reference them:

    session.customPropertiesFile=data/session-custom.json
    session.memberCustomPropertiesFile=data/member-custom.json

Each file must contain one JSON object. The member file is optional; the session
custom-properties file is mandatory for a live publication.

A live MPSD write requires all of the following:

1. `session.writeEnabled=true`
2. `nethernet.enabled=true`
3. `bedrock.redirectEnabled=true`
4. the selected template reports `connectivity=true`
5. `session.customPropertiesFile` is present and valid JSON

If any requirement fails, NovaBroadcast refuses to advertise the session. The
create call uses `If-None-Match: *` so an existing session with the same name is
not silently overwritten.

If `session.setActivity=true`, a successful publication is followed by the
standard Xbox MPSD `activity` handle write referencing the same SCID/template/
session name. This binds the published session as the signed-in user's current
join-in-progress/social activity. It is separately opt-in because setting a new
activity replaces the account's previous bound activity.

## Minecraft client authentication

NovaBroadcast uses the configured issuer's OpenID Connect discovery document:

    https://authorization.franchise.minecraft-services.net/.well-known/openid-configuration

For a client `a=identity`, NovaBroadcast verifies the GameServerToken signature,
time claims, issuer, multiplayer audience, P-384 `cpk`, IdP domain and detached
ES384 DTLS-fingerprint proof before allocating ICE/DTLS/SCTP state. Invalid
assertions are rejected at signaling time.

## Source provenance

All NovaBroadcast Java sources were written for this project. The WebRTC engine
is consumed as a normal general-purpose Apache-2.0 dependency. Public Microsoft,
Xbox and Mojang documentation is used for service/protocol behavior; independent
Bedrock protocol libraries are used only as interoperability cross-checks. No
MCXboxBroadcast/Broadcaster implementation source or runtime is used.
