# NovaBroadcast Clean-Room v0.3

This project was written from a blank project and does **not** include, download,
compile, shade, or launch MCXboxBroadcast/Broadcaster source or JAR files.

Current milestone:
- Java 21 standalone application
- Microsoft device-code sign-in and refresh-token cache
- Xbox User Token / XSTS exchange and profile lookup
- Clean MPSD session-directory client
- MPSD SCID/template authorization preflight
- Minimal authenticated-member session document builder
- Guarded MPSD create/read/leave lifecycle primitives
- NetherNet HTTP signaling endpoint foundation
- NetherNet reliable/unreliable one-byte data framing
- Reliable countdown fragmentation/reassembly
- Java 21 GitHub Actions build + self-tests
- No third-party Java dependencies

v0.3 still does **not** advertise a Minecraft joinable session or complete a
Bedrock WebRTC connection. It intentionally does not embed guessed Minecraft
Retail identifiers or pretend that normal HTTP/WebSocket code is a WebRTC
implementation.

## NetherNet foundation

When `nethernet.enabled=true`, NovaBroadcast starts the public partner signaling
shape documented by Mojang:

    GET  /v1/join
    POST /v1/join/{networkId}

`POST` accepts `application/sdp` and validates the basic SDP shape before passing
it to a peer backend. `NetworkID` is treated as an opaque path value rather than
assuming it is numeric.

The current `PeerBackend` intentionally reports not-ready, so `GET /v1/join`
returns `503` instead of falsely claiming the server can complete WebRTC. The
next transport milestone is the actual answering-side ICE/UDP + DTLS + SCTP
WebRTC peer implementation and server identity assertion.

The data-channel layer already implements the public NetherNet framing rules:
`ReliableDataChannel` supports countdown fragments ending at header `0`, while
`UnreliableDataChannel` only accepts an unfragmented header `0` frame.

## MPSD safety

When `session.enabled=true`, NovaBroadcast requires an explicit title-authorized
`session.scid` and `session.template`, checks that the template is reachable,
and renders the authenticated-member document. It does not embed title-owned
Minecraft constants.

`session.writeEnabled` is an additional guard. The current milestone still
refuses to publish while the Minecraft-specific session data and working
NetherNet peer transport are unfinished, preventing an unreachable Xbox session
from being advertised.

## Build and test

Linux / Java 21:

    ./build.sh
    java -jar NovaBroadcast.jar --self-test

Output:

    NovaBroadcast.jar

Pull requests and development branches are compiled and self-tested by GitHub
Actions on Java 21.

## Run

    java -jar NovaBroadcast.jar

On first launch `config.properties` is created. Set `microsoft.clientId` to an
application/client ID authorized for the Xbox Live scopes used by the account
flow, then start again. Tokens are cached in `data/auth.properties`.

Example dry-run configuration:

    session.enabled=true
    session.scid=<authorized SCID>
    session.template=<authorized session template>
    session.name=NovaBroadcast
    session.writeEnabled=false

    nethernet.enabled=true
    nethernet.listenHost=0.0.0.0
    nethernet.listenPort=19134
    nethernet.maxSdpBytes=1048576
    nethernet.maxSctpMessageSize=262144

With the current v0.3 peer backend, the signaling service will run but advertise
itself as unavailable until the real WebRTC backend is connected.

## Source provenance

All Java files in this repository were created for NovaBroadcast. The code uses
only Java's standard library. Public service endpoint names, signaling shapes,
and framing rules are based on public Microsoft/Xbox documentation and Mojang's
public Bedrock protocol documentation.
