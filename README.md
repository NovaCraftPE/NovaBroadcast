# NovaBroadcast Clean-Room v0.2

This project was written from a blank project and does **not** include, download,
compile, shade, or launch MCXboxBroadcast/Broadcaster source or JAR files.

Current milestone:
- Java 21 standalone application
- Microsoft device-code sign-in
- Refresh-token cache
- Xbox User Token exchange
- XSTS token exchange
- Xbox profile lookup
- Clean MPSD session-directory client
- MPSD SCID/template authorization preflight
- Safe session URI construction
- Clean NetherNet transport boundary
- No third-party Java dependencies

v0.2 still does **not** advertise a Minecraft joinable session or accept/transfer
Bedrock clients. When `session.enabled=true`, NovaBroadcast now requires an
explicit title-authorized `session.scid` and `session.template`, checks that the
template is reachable through MPSD, and then stops before writing a session.
It intentionally does not embed guessed Minecraft Retail identifiers.

NetherNet/WebRTC remains disabled until the ICE/DTLS/SCTP transport and the
Minecraft session document are implemented and tested against the current
Bedrock release.

## Build

Linux / Java 21:

    ./build.sh

Output:

    NovaBroadcast.jar

## Run

    java -jar NovaBroadcast.jar

On first launch `config.properties` is created. Set `microsoft.clientId` to an
application/client ID that is authorized for the Xbox Live scopes used by the
account flow, then start again.

The application prints a Microsoft device-code URL and code. Tokens are cached
in `data/auth.properties`.

To exercise the MPSD preflight, configure title-authorized values:

    session.enabled=true
    session.scid=<authorized SCID>
    session.template=<authorized session template>
    session.name=NovaBroadcast

Do not enable `nethernet.enabled` yet.

## Source provenance

All Java files in this repository were created for NovaBroadcast. The code uses
only Java's standard library. Public service endpoint names and request shapes
are based on public Microsoft/Xbox documentation and public Bedrock protocol
documentation.
