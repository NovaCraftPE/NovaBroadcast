# NovaBroadcast Clean-Room v0.5

NovaBroadcast is an independent Java implementation for Xbox/Bedrock session discovery, NetherNet/WebRTC admission and a narrow Bedrock redirect bootstrap. It does **not** include, download, compile, shade, inspect, or launch MCXboxBroadcast/Broadcaster source or JARs.

## Current milestone

- Java 21 standalone application
- Microsoft device-code sign-in and refresh-token cache
- Xbox User Token / XSTS authentication and profile lookup
- read-only Xbox Presence diagnostics
- MPSD template preflight, guarded session publication and Xbox activity-handle binding
- one-shot MPSD shutdown cleanup for normal exit, Ctrl+C and JVM/container shutdown
- authenticated account-owned MPSD activity discovery
- offline session candidate extraction and automatic consecutive-capture diffing
- NetherNet HTTP signaling (`GET /v1/join`, `POST /v1/join/{networkId}`)
- native answering-side WebRTC via Apache-2.0 `webrtc-java`
- persistent P-384 NetherNet operator identity and signed server `a=identity`
- Minecraft client GameServerToken/fingerprint-proof verification through OpenID discovery
- verified redirect-only Bedrock paths for protocol 2168 and 2169
- guarded automatic `TransferPacket` to the configured target
- RakNet UDP target-server reachability/protocol preflight
- explicit MPSD custom-property file support without guessed Minecraft-owned keys
- Java 21 CI, protocol/identity/redirect/import/diff/presence/target tests, native WebRTC smoke test and tested JAR artifact

## Build and tests

```bash
./build.sh
java -jar NovaBroadcast.jar --self-test
java -jar NovaBroadcast.jar --config-check
java -jar NovaBroadcast.jar --webrtc-smoke-test
```

On Debian/Ubuntu Linux, install the WebRTC JNI runtime dependency:

```bash
apt-get update && apt-get install -y --no-install-recommends libpulse0
```

## Run

```bash
java -jar NovaBroadcast.jar
```

On first launch `config.properties` is created. Set `microsoft.clientId` to an application/client ID authorized for the Xbox Live scopes used by the account flow. Tokens are cached in `data/auth.properties`.

## Validate the destination Bedrock server

Before any Xbox test, verify that the transfer target is actually reachable:

```bash
java -jar NovaBroadcast.jar --target-check
```

This sends a standard RakNet unconnected ping only. It does not log into the target. NovaBroadcast reports the target MOTD, Bedrock version, network protocol and player counts, then fails if the target's advertised protocol does not match `bedrock.gameVersion`.

## Read-only live readiness check

Once Xbox authentication and MPSD candidate values are configured, run:

```bash
java -jar NovaBroadcast.jar --live-preflight
```

This command does **not** create, update, join, leave or bind an MPSD session. It checks:

- configured Bedrock transfer target reachability and protocol match
- Xbox authentication
- current Xbox Presence/title records for the account
- account-owned MPSD activity query access
- configured MPSD template access and connectivity capability
- configured session/member custom JSON syntax

Presence is diagnostic only. NovaBroadcast does not manufacture or impersonate Minecraft title presence. A real Xbox/Minecraft session is still required to establish whether Xbox considers the account actively engaged in the Minecraft title during the live test.

## Discover your own Xbox/Minecraft activity

Use the account's own legitimate Minecraft session to obtain title/session metadata without guessing private constants:

```bash
java -jar NovaBroadcast.jar --discover-session
```

The command saves the raw account-owned response to:

```text
data/mpsd-activities.json
```

and prepares each complete `sessionRef` under:

```text
data/activity-import/candidate-N/
```

A candidate can contain:

```text
session.properties
session-custom.json
member-custom.json
```

`member-custom.json` is emitted only when the included member metadata is unambiguous. Every generated candidate deliberately contains:

```properties
session.writeEnabled=false
session.setActivity=false
```

so discovery cannot accidentally publish a captured session.

If a previous capture exists, NovaBroadcast rotates it to `data/mpsd-activities-previous.json`. A second `--discover-session` automatically compares the two captures and writes `data/activity-diff.txt`.

The diff matches activities by title ID + SCID + template rather than array position, so result reordering does not create false changes. Changed paths are grouped as `CUSTOM`, `SESSION_REF`, `SYSTEM`, or `OTHER` to help identify values tied to the original host/session.

The individual stages remain available:

```bash
java -jar NovaBroadcast.jar --dump-activities
java -jar NovaBroadcast.jar --prepare-activity-import
java -jar NovaBroadcast.jar --diff-last-activities
java -jar NovaBroadcast.jar --diff-activities before.json after.json
```

Captured title custom properties are reference data, not automatically publication-ready. NovaBroadcast intentionally does not infer which private Minecraft fields should be copied, replaced or regenerated.

## Bedrock redirect bootstrap

NovaBroadcast is not a general Bedrock server. It implements only enough of the initial Bedrock connection to reach a safe `TransferPacket`.

Verified versions:

- `1.26.40` -> protocol `2168`
- `1.26.43` -> protocol `2168`
- `1.26.44` -> protocol `2168`
- `1.26.45` -> protocol `2169`

The 1.26.45 mapping is based on Mojang's tagged schemas for the exact packets used by this redirect path: RequestNetworkSettings, NetworkSettings, Login, ResourcePacksInfo, ResourcePackStack, ResourcePackClientResponse and Transfer. Other protocol versions remain fail-closed until explicitly verified.

Example:

```properties
target.host=54.37.245.44
target.port=19133
target.name=NovaCraft
bedrock.redirectEnabled=false
bedrock.gameVersion=1.26.45
```

The narrow state machine is:

```text
RequestNetworkSettings
  -> NetworkSettings (Compression::None)
  -> Login
  -> PlayStatus(LoginSuccess) + empty ResourcePacksInfo
  -> empty ResourcePackStack
  -> TransferPacket
```

Post-NetworkSettings no-compression batches use method byte `0xFF`. NovaBroadcast skips the optional Bedrock encryption handshake and does not fabricate `StartGame`, chunks, entities or a fake world.

## NetherNet

Example configuration:

```properties
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
```

The signaling TCP port and configured ICE UDP range must be externally reachable. The client supplies its own opaque `NetworkID` in `POST /v1/join/{networkId}`; NovaBroadcast does not assume a numeric format.

## MPSD publication

MPSD session templates are title-owned resources configured through Xbox Partner Center. NovaBroadcast requires explicit authorized values:

```properties
session.enabled=false
session.scid=
session.template=
session.name=NovaBroadcast
session.writeEnabled=false
session.setActivity=false
session.customPropertiesFile=
session.memberCustomPropertiesFile=
```

Microsoft MPSD defines the session-system contract, while Minecraft-specific bootstrap values live in title custom properties. NovaBroadcast does not guess Minecraft's private custom-property names, Retail SCID or template.

A live write requires all of the following:

1. `session.writeEnabled=true`
2. `nethernet.enabled=true`
3. `bedrock.redirectEnabled=true`
4. the selected template reports `connectivity=true`
5. `session.customPropertiesFile` is present and valid JSON

The create uses `If-None-Match: *`, preventing silent replacement of an existing session with the same name.

When `session.setActivity=true`, a successful publication is followed by an Xbox MPSD `activity` handle referencing the same SCID/template/session. If activity binding fails, NovaBroadcast attempts to leave the newly created member before surfacing the error.

After a successful publication, `MpsdSessionLease` owns the active membership. Normal shutdown, Ctrl+C or JVM/container termination performs a one-shot member leave. MPSD's normal lifecycle then removes the bound activity handle, reducing stale joinable sessions after the NetherNet/WebRTC host stops.

## Minecraft client authentication

NovaBroadcast uses the configured Minecraft multiplayer OpenID issuer. For client `a=identity`, it verifies the GameServerToken signature, time claims, issuer, multiplayer audience, P-384 `cpk`, IdP domain and detached ES384 DTLS-fingerprint proof before allocating ICE/DTLS/SCTP state. Invalid assertions are rejected during signaling.

## Remaining live-only validation

The offline/server-side implementation is intentionally not described as Xbox-console validated yet. The final interoperability milestone requires a genuine Minecraft/Xbox activity owned by the authenticated account, a real MPSD publication using verified title-specific metadata, and a friend/console join that reaches NetherNet and receives the final Bedrock transfer.

Xbox Presence is part of that validation. Microsoft can mark an active MPSD member inactive when Presence no longer considers the user engaged in the title. NovaBroadcast therefore reports Presence during `--live-preflight` rather than inventing title engagement or private title credentials.

## Source provenance

All NovaBroadcast project Java sources were written independently for this project. Public Microsoft/Xbox and Mojang documentation is used for service/protocol behavior. The WebRTC engine is consumed as a normal general-purpose Apache-2.0 dependency. Independent Bedrock/RakNet specifications are used only for interoperability cross-checks. No MCXboxBroadcast/Broadcaster implementation source or runtime is used.
