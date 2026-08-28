package uk.blazecraft.novabroadcast;

import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Bedrock/Xbox authentication backed by the public MinecraftAuth library. */
final class MinecraftBedrockAuth {
    private static final Path CACHE = Path.of("data/minecraft-bedrock-auth.json");

    record Result(XboxIdentity identity, String pmsgId) {}

    XboxIdentity authenticate(String bedrockVersion) throws Exception {
        return authenticateDetailed(bedrockVersion).identity();
    }

    Result authenticateDetailed(String bedrockVersion) throws Exception {
        var httpClient = MinecraftAuth.createHttpClient();
        BedrockAuthManager manager = null;

        if (Files.isRegularFile(CACHE)) {
            try {
                String cached = Files.readString(CACHE).trim();
                if (!cached.isBlank()) {
                    manager = BedrockAuthManager.fromJson(
                            httpClient,
                            bedrockVersion,
                            com.google.gson.JsonParser.parseString(cached).getAsJsonObject());
                    System.out.println("[BedrockAuth] Loaded cached Bedrock authentication.");
                }
            } catch (Exception e) {
                System.out.println("[BedrockAuth] Cached authentication could not be loaded; a new sign-in will be requested.");
                manager = null;
            }
        }

        if (manager == null) {
            Consumer<MsaDeviceCode> deviceCode = code -> {
                System.out.println("[BedrockAuth] Open " + code.getVerificationUri());
                System.out.println("[BedrockAuth] Enter code: " + code.getUserCode());
            };
            manager = BedrockAuthManager.create(httpClient, bedrockVersion)
                    .login(DeviceCodeMsaAuthService::new, deviceCode);
        }

        // Refresh the same token chain used by a Bedrock session before reading any
        // Minecraft-session claims. A deserialized cache may have a stale/empty
        // Minecraft session even while its Xbox XSTS token is still usable.
        var xsts = manager.getXboxLiveXstsToken().getUpToDate();
        manager.getPlayFabToken().getUpToDate();
        var minecraftSession = manager.getMinecraftSession().getUpToDate();

        String authorization = xsts.getAuthorizationHeader();
        if (authorization == null || authorization.isBlank()) {
            throw new IllegalStateException("Bedrock authentication returned an empty Xbox authorization header.");
        }

        String pmsgId;
        try {
            pmsgId = minecraftSession.getParsedToken().getPayload().reqString("pmid");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Fresh Minecraft session token did not expose the required pmid claim. " +
                    "Delete data/minecraft-bedrock-auth.json and run the preflight once more to force a fresh device-code login.", e);
        }
        if (pmsgId == null || pmsgId.isBlank()) {
            throw new IllegalStateException("Fresh Minecraft session token returned an empty pmid claim.");
        }
        System.out.println("[BedrockAuth] Minecraft session pmid is available.");

        Files.createDirectories(CACHE.toAbsolutePath().getParent());
        Files.writeString(CACHE, BedrockAuthManager.toJson(manager).toString());

        Http.Response profile = Http.get(
                "https://profile.xboxlive.com/users/me/profile/settings?settings=Gamertag",
                Map.of(
                        "Authorization", authorization,
                        "x-xbl-contract-version", "3",
                        "Accept", "application/json"));

        String xuid = "";
        String gamertag = "";
        if (profile.ok()) {
            xuid = Json.nestedString(profile.body(), "profileUsers", "0", "id");
            gamertag = Json.nestedString(profile.body(), "profileUsers", "0", "settings", "0", "value");
        }

        System.out.println("[BedrockAuth] Authenticated with MinecraftAuth Bedrock flow.");
        if (!gamertag.isBlank()) System.out.println("[BedrockAuth] Gamertag: " + gamertag);
        if (!xuid.isBlank()) System.out.println("[BedrockAuth] XUID: " + xuid);

        return new Result(new XboxIdentity("", "", xuid, gamertag, authorization), pmsgId);
    }
}
