# NovaBroadcast NetherNet implementation notes

NovaBroadcast is a clean-room implementation. These notes document protocol facts from public specifications; no MCXboxBroadcast source is used.

## Current target

1. Authenticate Microsoft/Xbox account.
2. Advertise a Minecraft-compatible Xbox multiplayer session through MPSD.
3. Publish a NetherNet/WebRTC network identifier in the session.
4. Accept the Minecraft client's WebRTC connection.
5. Carry Bedrock packets over the reliable/unreliable SCTP data channels.
6. Send a Bedrock TransferPacket directing the player to the configured BedrockConnect endpoint.

## Public protocol facts

MPSD sessions require a predefined session template associated with a service configuration identifier (SCID). The exact Minecraft Retail SCID/template and title-authorized request requirements must be established before NovaBroadcast can safely enable session advertising.

Current NetherNet is WebRTC-based. Mojang's public partner onboarding documentation describes two SCTP data channels named `ReliableDataChannel` and `UnreliableDataChannel`. HTTP signaling uses `GET /v1/join` as a capability check and `POST /v1/join/{networkId}` with an SDP offer/answer exchange. The answer requires a valid identity assertion and DTLS fingerprint binding.

Xbox/franchise discovery is a different signaling route. Public interoperability research describes a `SupportedConnections` entry containing a WebRTC/NetherNet network ID and authenticated signaling. NovaBroadcast must not assume that the HTTP partner flow and Xbox franchise flow are interchangeable.

## Important blocker

A normal Java HTTP/WebSocket client is not a WebRTC implementation. The host side requires ICE/UDP, DTLS, SCTP and WebRTC data channels. Implementing that entire stack ourselves is a large separate networking project. NovaBroadcast can remain an independent application while using a general-purpose WebRTC transport library; that does not make it an MCXboxBroadcast derivative.

## Security

Never commit Microsoft refresh tokens, Xbox/XSTS tokens, PlayFab/Minecraft tokens, private identity keys, or runtime auth files. Runtime secrets belong under `data/`, which is ignored by git.

## References

- Microsoft Xbox MPSD documentation: session templates and Session Directory REST API.
- Mojang `bedrock-protocol-docs`, including `NetherNetOnboardingGuide.md`.
- `df-mc/nethernet-spec` only as an interoperability/protocol reference for the Xbox/franchise signaling route.

Protocol details are version-sensitive and must be tested against the current Bedrock release before being marked production-ready.
