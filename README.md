# NovaBroadcast Clean-Room v0.4

NovaBroadcast is an independent Java implementation and does **not** include,
download, compile, shade, or launch MCXboxBroadcast/Broadcaster source or JARs.

Current milestone:
- Java 21 standalone application
- Microsoft device-code sign-in and refresh-token cache
- Xbox User Token / XSTS exchange and profile lookup
- clean MPSD preflight and guarded session lifecycle
- NetherNet HTTP signaling (`GET /v1/join`, `POST /v1/join/{networkId}`)
- NetherNet reliable/unreliable one-byte framing
- reliable countdown fragmentation/reassembly
- native answering-side WebRTC peer creation via the general-purpose Apache-2.0 `webrtc-java` library
- ICE gathering, DTLS/SCTP negotiation handled by the native WebRTC stack
- incoming `ReliableDataChannel` and `UnreliableDataChannel` registration
- fixed configurable ICE UDP port range for container/Pterodactyl deployments
- Java 21 CI, protocol self-tests, and native WebRTC smoke test

v0.4 can create a real WebRTC answering peer and return the gathered local SDP.
It still deliberately does **not** publish a Minecraft/Xbox joinable session.
Minecraft-specific MPSD properties and the required server identity assertion must
be correct before `session.writeEnabled` can safely publish anything.

## Build and test

Requires Java 21 and Maven:

    ./build.sh
    java -jar NovaBroadcast.jar --self-test
    java -jar NovaBroadcast.jar --webrtc-smoke-test

Output:

    NovaBroadcast.jar

The build uses `dev.onvoid.webrtc:webrtc-java:0.14.0`, which supplies the
platform WebRTC JNI implementation. GitHub Actions verifies both normal logic
and that the native WebRTC factory can actually load from the packaged JAR.

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
    nethernet.stunUrl=stun:stun.l.google.com:19302
    nethernet.iceMinPort=20000
    nethernet.iceMaxPort=20100

The signaling TCP port and the configured ICE UDP range must be reachable from
outside the container. On Pterodactyl, allocate/map the UDP range before testing
real Bedrock connections.

When an SDP offer is received, NovaBroadcast now:
1. creates a native WebRTC peer,
2. applies the remote offer,
3. creates and sets a local answer,
4. waits for ICE gathering to complete,
5. returns the final local SDP with gathered candidates,
6. accepts `ReliableDataChannel` / `UnreliableDataChannel`, and
7. feeds received frames into the clean-room NetherNet framing layer.

Received Bedrock payloads are currently surfaced to the transport boundary only.
The next milestone is the Bedrock packet bridge/TransferPacket path plus the
Minecraft-compatible identity/session metadata needed before MPSD publication.

## MPSD safety

`session.scid` and `session.template` must be explicit title-authorized values.
NovaBroadcast does not embed guessed Minecraft Retail identifiers.

`session.writeEnabled=false` remains the safe default. The project continues to
refuse to publish an unreachable or incorrectly-described Xbox session while the
remaining Minecraft-specific identity/session work is unfinished.

## Source provenance

All NovaBroadcast Java sources were written for this project. The WebRTC engine
is consumed as an ordinary general-purpose Apache-2.0 Maven dependency; no
broadcaster-specific implementation is copied or used at runtime. Public service
endpoint names, signaling shapes, and framing rules are based on public
Microsoft/Xbox and Mojang Bedrock documentation.
