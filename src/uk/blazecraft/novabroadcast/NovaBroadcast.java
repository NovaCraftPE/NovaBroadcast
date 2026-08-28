package uk.blazecraft.novabroadcast;

import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

public final class NovaBroadcast {
    public static final String VERSION = "0.5-cleanroom";

    public static void main(String[] args) {
        try {
            if (Arrays.asList(args).contains("--self-test")) {
                SelfTest.run();
                BedrockRedirectSelfTest.run();
                ActivityDiffSelfTest.run();
                BedrockTargetProbeSelfTest.run();
                return;
            }
            if (Arrays.asList(args).contains("--webrtc-smoke-test")) {
                SelfTest.runNativeWebRtc();
                return;
            }
            if (Arrays.asList(args).contains("--config-check")) {
                ConfigCheck.run(Path.of("config.properties"));
                return;
            }
            if (Arrays.asList(args).contains("--target-check")) {
                targetCheck();
                return;
            }
            if (Arrays.asList(args).contains("--prepare-activity-import")) {
                prepareActivityImport();
                return;
            }
            if (Arrays.asList(args).contains("--diff-activities")) {
                diffActivities(args);
                return;
            }
            if (Arrays.asList(args).contains("--diff-last-activities")) {
                ActivityDiff.run(Path.of("data/mpsd-activities-previous.json"),
                        Path.of("data/mpsd-activities.json"), Path.of("data/activity-diff.txt"));
                return;
            }

            System.out.println("NovaBroadcast " + VERSION);
            System.out.println("Independent Java implementation - no MCXboxBroadcast runtime/source dependency.");

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
            if (!xbox.gamertag().isBlank()) System.out.println("[Xbox] Gamertag: " + xbox.gamertag());
            if (!xbox.xuid().isBlank()) System.out.println("[Xbox] XUID: " + xbox.xuid());

            if (Arrays.asList(args).contains("--live-preflight")) {
                livePreflight(config, xbox);
                return;
            }

            boolean dumpActivities = Arrays.asList(args).contains("--dump-activities");
            boolean discoverSession = Arrays.asList(args).contains("--discover-session");
            if (dumpActivities || discoverSession) {
                Path current = Path.of("data/mpsd-activities.json");
                Path previous = Path.of("data/mpsd-activities-previous.json");
                if (Files.isRegularFile(current)) {
                    Files.createDirectories(previous.toAbsolutePath().getParent());
                    Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Session] Preserved previous activity dump at " + previous.toAbsolutePath());
                }
                SessionDirectoryClient sessions = new SessionDirectoryClient(xbox);
                sessions.dumpOwnActivities(current);
                System.out.println("[NovaBroadcast] Activity discovery complete. No session was created or modified.");
                if (discoverSession) {
                    prepareActivityImport();
                    if (Files.isRegularFile(previous)) {
                        ActivityDiff.run(previous, current, Path.of("data/activity-diff.txt"));
                        System.out.println("[NovaBroadcast] Consecutive discovery comparison completed automatically.");
                    } else {
                        System.out.println("[NovaBroadcast] First discovery saved. Run --discover-session again during another Minecraft activity to generate a diff.");
                    }
                } else if (Files.isRegularFile(previous)) {
                    System.out.println("[NovaBroadcast] Compare the last two captures with --diff-last-activities.");
                }
                return;
            }

            MpsdSessionLease sessionLease = null;
            try (NetherNetTransport transport = new NetherNetTransport()) {
                transport.start(config);

                if (config.sessionEnabled()) {
                    SessionDirectoryClient sessions = new SessionDirectoryClient(xbox);
                    if (sessions.start(config)) sessionLease = new MpsdSessionLease(sessions, config);
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
            } finally {
                if (sessionLease != null) sessionLease.close();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[NovaBroadcast] Stopped.");
        } catch (Exception e) {
            System.err.println("[NovaBroadcast] " + e.getMessage());
            if (Boolean.getBoolean("novabroadcast.debug")) e.printStackTrace();
            System.exit(1);
        }
    }

    private static void livePreflight(AppConfig config, XboxIdentity xbox) throws Exception {
        System.out.println("[LivePreflight] Running read-only readiness checks. No MPSD session will be published.");
        BedrockTargetProbe.Result target = BedrockTargetProbe.probe(config.targetHost(), config.targetPort(), 3000);
        System.out.println("[LivePreflight] Target: " + target.motd() + " / " + target.version() +
                " / protocol " + target.protocol());
        int expected = BedrockProtocolVersions.requireProtocol(config.bedrockGameVersion());
        if (target.protocol() != expected) {
            throw new IllegalStateException("[LivePreflight] FAIL target advertises protocol " + target.protocol() +
                    " but bedrock.gameVersion=" + config.bedrockGameVersion() + " expects " + expected);
        }
        System.out.println("[LivePreflight] Target protocol matches configured redirect version.");

        SessionDirectoryClient sessions = new SessionDirectoryClient(xbox);
        String activities = sessions.ownActivities();
        System.out.println("[LivePreflight] Authenticated MPSD activity query succeeded; handles=" +
                SessionDirectoryClient.activityCount(activities));
        SessionDirectoryClient.printActivitySummary(activities);
        sessions.preflightOnly(config);
        System.out.println("[LivePreflight] PASS read-only checks completed. This does not prove Xbox Presence/title engagement or console joinability; those require the real Minecraft/Xbox test.");
    }

    private static void targetCheck() throws Exception {
        AppConfig config = AppConfig.load(Path.of("config.properties"));
        System.out.println("[TargetCheck] Probing " + config.targetHost() + ":" + config.targetPort() + " over RakNet UDP...");
        BedrockTargetProbe.Result result = BedrockTargetProbe.probe(config.targetHost(), config.targetPort(), 3000);
        System.out.println("[TargetCheck] MOTD: " + result.motd());
        System.out.println("[TargetCheck] Edition: " + result.edition());
        System.out.println("[TargetCheck] Version: " + result.version() + " / protocol " + result.protocol());
        System.out.println("[TargetCheck] Players: " + result.players() + "/" + result.maxPlayers());
        int expected = BedrockProtocolVersions.requireProtocol(config.bedrockGameVersion());
        if (result.protocol() != expected) {
            throw new IllegalStateException("[TargetCheck] FAIL target advertises protocol " + result.protocol() +
                    " but bedrock.gameVersion=" + config.bedrockGameVersion() + " expects " + expected);
        }
        System.out.println("[TargetCheck] PASS target is reachable and protocol matches the configured redirect version.");
    }

    private static void prepareActivityImport() throws Exception {
        Path dump = Path.of("data/mpsd-activities.json");
        Path output = Path.of("data/activity-import");
        List<Path> candidates = ActivityImport.prepare(dump, output);
        System.out.println("[ActivityImport] Prepared " + candidates.size() + " candidate(s) under " + output.toAbsolutePath());
        for (Path candidate : candidates) {
            System.out.println("[ActivityImport] " + candidate.resolve("session.properties").toAbsolutePath());
        }
        if (candidates.isEmpty()) {
            System.out.println("[ActivityImport] No complete sessionRef candidates were present in the dump.");
        }
        System.out.println("[ActivityImport] Main config was not modified and publishing remains disabled.");
    }

    private static void diffActivities(String[] args) throws Exception {
        int i = Arrays.asList(args).indexOf("--diff-activities");
        if (i < 0 || i + 2 >= args.length) {
            throw new IllegalArgumentException("Usage: --diff-activities <before.json> <after.json>");
        }
        ActivityDiff.run(Path.of(args[i + 1]), Path.of(args[i + 2]), Path.of("data/activity-diff.txt"));
    }
}
