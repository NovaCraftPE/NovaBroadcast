package uk.blazecraft.novabroadcast;

import java.nio.file.*;
import java.util.Arrays;

public final class NovaBroadcast {
    public static final String VERSION = "0.4-cleanroom";

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--self-test")) {
            SelfTest.run();
            return;
        }
        if (Arrays.asList(args).contains("--webrtc-smoke-test")) {
            SelfTest.runNativeWebRtc();
            return;
        }

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

            try (NetherNetTransport transport = new NetherNetTransport()) {
                transport.start(config);

                if (config.sessionEnabled()) {
                    SessionDirectoryClient sessions = new SessionDirectoryClient(xbox);
                    sessions.start(config);
                } else {
                    System.out.println();
                    System.out.println("[Session] MPSD advertising is disabled.");
                }

                if (config.netherNetEnabled()) {
                    System.out.println("[NovaBroadcast] NetherNet signaling/WebRTC service is running. Press Ctrl+C to stop.");
                    transport.await();
                } else {
                    System.out.println("[NovaBroadcast] Authentication/core milestone completed successfully.");
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[NovaBroadcast] Stopped.");
        } catch (Exception e) {
            System.err.println("[NovaBroadcast] " + e.getMessage());
            if (Boolean.getBoolean("novabroadcast.debug")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
}
