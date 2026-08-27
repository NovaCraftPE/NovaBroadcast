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
- ICE gathering and DTLS/SCTP negotiation handled by the native WebRTC stack
- headless WebRTC factory using the dummy audio layer
- incoming and outbound `ReliableDataChannel` / `UnreliableDataChannel` handling
- fixed configurable ICE UDP port range for container/Pterodactyl deployments
- version-aware Bedrock `TransferPacket` encoder boundary
- conservative live Bedrock wire inspection for direct and exact-length-prefixed packet shapes
- Java 21 CI, protocol self-tests, exact transfer-byte tests, and a native WebRTC smoke test

v0.4 can create a real WebRTC answering peer, gather ICE, return the final local
SDP, and exchange framed binary application data over the two NetherNet data
channels. It still deliberately does **not** publish a Minecraft/Xbox joinable
session. Minecraft-specific MPSD properties and the required server identity
assertion must be correct before `session.writeEnabled` can safely publish
anything.

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
    nethernet.stunUrl=stun:stun.l.google.com:19302
    nethernet.iceMinPort=20000
    nethernet.iceMaxPort=20100

The signaling TCP port and the configured ICE UDP range must be reachable from
outside the container. On Pterodactyl, allocate/map the UDP range before testing
real Bedrock connections.

When an SDP offer is received, NovaBroadcast now:
1. creates a headless native WebRTC peer,
2. applies the remote offer,
3. creates and sets a local answer,
4. waits for ICE gathering to complete,
5. returns the final local SDP with gathered candidates,
6. accepts `ReliableDataChannel` / `UnreliableDataChannel`,
7. reassembles/strips the documented NetherNet framing, and
8. diagnostically inspects the recovered application payload without mutating it.

Outbound application payloads can also be sent through the same channels.
Reliable payloads are fragmented according to the NetherNet countdown framing;
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

To make the next real-client test useful without guessing, incoming application
payloads are inspected conservatively. NovaBroadcast logs a packet ID/sub-client
header only when the bytes structurally match either a direct Bedrock packet or
an exact VarUInt-length-prefixed packet. Unknown/enveloped payloads remain
untouched and are reported as such.

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
Microsoft/Xbox and Mojang Bedrock documentation. Independent protocol libraries
and specifications may be used only as interoperability cross-checks where the
public Mojang documents do not specify an inner transport detail.
