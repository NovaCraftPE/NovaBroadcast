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
        boolean netherNetEnabled) {

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
                Integer.parseInt(p.getProperty("target.port", "19132").trim()),
                p.getProperty("target.name", "NovaCraft").trim(),
                Boolean.parseBoolean(p.getProperty("session.enabled", "false")),
                Boolean.parseBoolean(p.getProperty("nethernet.enabled", "false"))
        );
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
session.enabled=false
nethernet.enabled=false
""";
    private DefaultConfig() {}
}
