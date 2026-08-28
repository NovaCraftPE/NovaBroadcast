package uk.blazecraft.novabroadcast;

import java.nio.file.Path;

/** Direct entry point for the guarded persistent Minecraft broadcast mode. */
public final class MinecraftLiveMain {
    public static void main(String[] args) {
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
    }

    private MinecraftLiveMain() {}
}
