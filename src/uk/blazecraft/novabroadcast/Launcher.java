package uk.blazecraft.novabroadcast;

import java.nio.file.Path;
import java.util.Arrays;

/** Executable JAR entry point that preserves legacy commands and adds live mode. */
public final class Launcher {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--minecraft-live")) {
            System.out.println("NovaBroadcast 0.9-minecraft-live");
            System.out.println("Persistent MinecraftLobby + Xbox-RPC NetherNet redirect mode.");
            try (MinecraftLiveBroadcast live = new MinecraftLiveBroadcast()) {
                AppConfig config = AppConfig.load(Path.of("config.properties"));
                live.run(config);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[MinecraftLive] Stopped.");
            } catch (Exception e) {
                System.err.println("[MinecraftLive] " + e.getMessage());
                if (Boolean.getBoolean("novabroadcast.debug")) e.printStackTrace();
                System.exit(1);
            }
            return;
        }

        NovaBroadcast.main(args);
    }

    private Launcher() {}
}
