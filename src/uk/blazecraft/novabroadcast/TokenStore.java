package uk.blazecraft.novabroadcast;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class TokenStore {
    private final Path path;

    TokenStore(Path path) { this.path = path; }

    MicrosoftTokens load() throws IOException {
        if (!Files.exists(path)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        String access = p.getProperty("access_token", "");
        String refresh = p.getProperty("refresh_token", "");
        long expires = Long.parseLong(p.getProperty("expires_at", "0"));
        if (refresh.isBlank()) return null;
        return new MicrosoftTokens(access, refresh, expires);
    }

    void save(MicrosoftTokens t) throws IOException {
        Files.createDirectories(path.getParent());
        Properties p = new Properties();
        p.setProperty("access_token", t.accessToken());
        p.setProperty("refresh_token", t.refreshToken());
        p.setProperty("expires_at", Long.toString(t.expiresAtEpochSeconds()));
        try (OutputStream out = Files.newOutputStream(path)) {
            p.store(out, "NovaBroadcast authentication cache - keep private");
        }
    }
}
