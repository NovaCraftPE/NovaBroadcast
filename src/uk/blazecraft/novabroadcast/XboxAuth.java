package uk.blazecraft.novabroadcast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class XboxAuth {
    XboxIdentity authenticate(String microsoftAccessToken, String relyingParty, String sandboxId) throws Exception {
        if (sandboxId == null || sandboxId.isBlank()) {
            throw new IllegalArgumentException("Xbox sandbox ID must not be blank.");
        }
        String userBody = """
{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":%s},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
""".formatted(Json.quote("d=" + microsoftAccessToken));

        Http.Response ur = Http.post(
                "https://user.auth.xboxlive.com/user/authenticate",
                userBody,
                Map.of("Content-Type","application/json","x-xbl-contract-version","1"));
        ur.requireOk("Xbox user authentication");

        String userToken = Json.string(ur.body(), "Token");
        String userHash = Json.nestedString(ur.body(), "DisplayClaims", "xui", "0", "uhs");
        if (userToken.isBlank() || userHash.isBlank()) {
            throw new IllegalStateException("Xbox user-auth response was missing Token/uhs.");
        }

        String xstsBody = """
{"Properties":{"SandboxId":%s,"UserTokens":[%s]},"RelyingParty":%s,"TokenType":"JWT"}
""".formatted(Json.quote(sandboxId), Json.quote(userToken), Json.quote(relyingParty));

        Http.Response xr = Http.post(
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                xstsBody,
                Map.of("Content-Type","application/json","x-xbl-contract-version","1"));
        xr.requireOk("Xbox XSTS authorization");

        String xsts = Json.string(xr.body(), "Token");
        String xuid = Json.nestedString(xr.body(), "DisplayClaims", "xui", "0", "xid");
        String gt = Json.nestedString(xr.body(), "DisplayClaims", "xui", "0", "gtg");
        String auth = "XBL3.0 x=" + userHash + ";" + xsts;

        try {
            String settings = URLEncoder.encode("Gamertag", StandardCharsets.UTF_8);
            Http.Response pr = Http.get(
                    "https://profile.xboxlive.com/users/me/profile/settings?settings=" + settings,
                    Map.of(
                            "Authorization", auth,
                            "x-xbl-contract-version","3",
                            "Accept","application/json"));
            if (pr.ok()) {
                String fetchedGt = Json.nestedString(pr.body(), "profileUsers", "0", "settings", "0", "value");
                String fetchedXuid = Json.nestedString(pr.body(), "profileUsers", "0", "id");
                if (!fetchedGt.isBlank()) gt = fetchedGt;
                if (!fetchedXuid.isBlank()) xuid = fetchedXuid;
            }
        } catch (Exception ignored) {}

        return new XboxIdentity(userHash, xsts, xuid, gt, auth);
    }
}
