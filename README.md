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
- Minimal authenticated-member session document builder
- Guarded MPSD create/read/leave lifecycle primitives
- Java 21 GitHub Actions build
- Clean NetherNet transport boundary
- No third-party Java dependencies

v0.2 still does **not** advertise a Minecraft joinable session or accept/transfer
Bedrock clients. When `session.enabled=true`, NovaBroadcast requires an explicit
title-authorized `session.scid` and `session.template`, checks that the template
is reachable through MPSD, and renders the base authenticated-member document.
It intentionally does not embed guessed Minecraft Retail identifiers.

The base MPSD document contains only public-schema member state: the authenticated
`members.me` XUID and `properties.system.active=true`. Lifecycle code exists for
conditional session creation (`If-None-Match: *`), readback, and leaving through
`/members/me`.

`session.writeEnabled` is an additional safety guard. Even when enabled, the
current milestone refuses to publish while the Minecraft-specific session data
and NetherNet/WebRTC transport are unfinished. This prevents an unreachable or
misleading Xbox session from being advertised.

## Build

Linux / Java 21:

    ./build.sh

Output:

    NovaBroadcast.jar

Pull requests and development branches are also compiled by GitHub Actions on
Java 21.

## Run

    java -jar NovaBroadcast.jar

On first launch `config.properties` is created. Set `microsoft.clientId` to an
application/client ID that is authorized for the Xbox Live scopes used by the
account flow, then start again.

The application prints a Microsoft device-code URL and code. Tokens are cached
in `data/auth.properties`.

To exercise the MPSD preflight/dry run, configure title-authorized values:

    session.enabled=true
    session.scid=<authorized SCID>
    session.template=<authorized session template>
    session.name=NovaBroadcast
    session.writeEnabled=false

Keep both `session.writeEnabled` and `nethernet.enabled` disabled for normal
v0.2 testing.

## Source provenance

All Java files in this repository were created for NovaBroadcast. The code uses
only Java's standard library. Public service endpoint names and request shapes
are based on public Microsoft/Xbox documentation and public Bedrock protocol
documentation.
