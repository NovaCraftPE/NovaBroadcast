package uk.blazecraft.novabroadcast;

import java.nio.file.*;

public final class NovaBroadcast {
    public static final String VERSION = "0.2-cleanroom";

    public static void main(String[] args) {
        System.out.println("NovaBroadcast " + VERSION);
        System.out.println("Independent Java implementation - no MCXboxBroadcast runtime/source dependency.");

        try {
            AppConfig config = AppConfig.load(Path.of("config.properties"));
            if (config.clientId().isBlank()) {
                System.out.println();
                System.out.println("Microsoft client ID is not configured.");
                System.out.println("Edit config.properties and set microsoft.clientId, then start again.");
                return;
            }

            TokenStore store = new TokenStore(Path.of("data/auth.properties"));
            MicrosoftAuth microsoft = new MicrosoftAuth(config, store);
            MicrosoftTokens msa = microsoft.getTokens();

            XboxAuth xboxAuth = new XboxAuth();
            XboxIdentity xbox = xboxAuth.authenticate(msa.accessToken(), config.xboxRelyingParty());

            System.out.println("[Xbox] Authenticated.");
            if (!xbox.gamertag().isBlank()) {
                System.out.println("[Xbox] Gamertag: " + xbox.gamertag());
            }
            if (!xbox.xuid().isBlank()) {
                System.out.println("[Xbox] XUID: " + xbox.xuid());
            }

            if (!config.sessionEnabled()) {
                System.out.println();
                System.out.println("[Session] MPSD advertising is disabled.");
                System.out.println("[Session] Authentication/core milestone completed successfully.");
                return;
            }

            SessionDirectoryClient sessions = new SessionDirectoryClient(xbox);
            sessions.start(config);

        } catch (Exception e) {
            System.err.println("[NovaBroadcast] " + e.getMessage());
            if (Boolean.getBoolean("novabroadcast.debug")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
}
