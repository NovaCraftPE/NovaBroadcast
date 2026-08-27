package uk.blazecraft.novabroadcast;

import java.io.*;
import java.nio.file.*;
import java.util.*;

record AppConfig(
        String clientId,
        String scope,
        String tenant,
        String xboxRelyingParty,
        String targetHost,
        int targetPort,
        String targetName,
        boolean sessionEnabled,
        boolean sessionWriteEnabled,
        boolean netherNetEnabled,
        String sessionScid,
        String sessionTemplate,
        String sessionName,
        String netherNetListenHost,
        int netherNetListenPort,
        int netherNetMaxSdpBytes,
        int netherNetMaxSctpMessageSize) {

    static AppConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, DefaultConfig.TEXT);
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }
        return new AppConfig(
                p.getProperty("microsoft.clientId", "").trim(),
                p.getProperty("microsoft.scope", "XboxLive.signin XboxLive.offline_access").trim(),
                p.getProperty("microsoft.tenant", "consumers").trim(),
                p.getProperty("xbox.relyingParty", "http://xboxlive.com").trim(),
                p.getProperty("target.host", "127.0.0.1").trim(),
                parsePort(p, "target.port", 19132),
                p.getProperty("target.name", "NovaCraft").trim(),
                Boolean.parseBoolean(p.getProperty("session.enabled", "false")),
                Boolean.parseBoolean(p.getProperty("session.writeEnabled", "false")),
                Boolean.parseBoolean(p.getProperty("nethernet.enabled", "false")),
                p.getProperty("session.scid", "").trim(),
                p.getProperty("session.template", "").trim(),
                p.getProperty("session.name", "NovaBroadcast").trim(),
                p.getProperty("nethernet.listenHost", "0.0.0.0").trim(),
                parsePort(p, "nethernet.listenPort", 19134),
                parsePositive(p, "nethernet.maxSdpBytes", 1_048_576),
                parsePositive(p, "nethernet.maxSctpMessageSize", 262_144)
        );
    }

    private static int parsePort(Properties p, String key, int fallback) {
        int value = parsePositive(p, key, fallback);
        if (value > 65535) throw new IllegalArgumentException(key + " must be <= 65535");
        return value;
    }

    private static int parsePositive(Properties p, String key, int fallback) {
        String raw = p.getProperty(key, Integer.toString(fallback)).trim();
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) throw new IllegalArgumentException(key + " must be > 0");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw);
        }
    }
}

final class DefaultConfig {
    static final String TEXT = """
# NovaBroadcast clean-room configuration
microsoft.clientId=
microsoft.scope=XboxLive.signin XboxLive.offline_access
microsoft.tenant=consumers
xbox.relyingParty=http://xboxlive.com
target.host=54.37.245.44
target.port=19133
target.name=NovaCraft

# Xbox Multiplayer Session Directory (MPSD)
# session.enabled runs the authenticated template preflight.
session.enabled=false
session.scid=
session.template=
session.name=NovaBroadcast

# Extra guard for real MPSD PUT/DELETE operations. Leave false while testing.
session.writeEnabled=false

# NetherNet HTTP signaling foundation.
# Until a WebRTC peer backend is implemented, capability checks return 503.
nethernet.enabled=false
nethernet.listenHost=0.0.0.0
nethernet.listenPort=19134
nethernet.maxSdpBytes=1048576
nethernet.maxSctpMessageSize=262144
""";
    private DefaultConfig() {}
}
