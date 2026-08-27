# NovaBroadcast Clean-Room v0.1

This project was written from a blank project and does **not** include, download,
compile, shade, or launch MCXboxBroadcast/Broadcaster source or JAR files.

Current milestone:
- Java 21 standalone application
- Microsoft device-code sign-in
- Refresh-token cache
- Xbox User Token exchange
- XSTS token exchange
- Xbox profile lookup
- Clean interfaces for Session Directory and NetherNet work
- No third-party Java dependencies

Important: v0.1 is the clean-room authentication/core milestone. It does NOT yet
advertise a Minecraft joinable session or accept/transfer Bedrock clients.
Those pieces require the Minecraft/Xbox session document plus NetherNet/WebRTC
signalling implementation and are intentionally not faked in this version.

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

## Source provenance

All Java files in this archive were created for NovaBroadcast. The code uses
only Java's standard library. Public service endpoint names and request shapes
are based on Microsoft documentation.
