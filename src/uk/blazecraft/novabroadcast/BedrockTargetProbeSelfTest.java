package uk.blazecraft.novabroadcast;

final class BedrockTargetProbeSelfTest {
    static void run() {
        String advertisement = "MCPE;NovaCraft;2169;1.26.45;3;20;123456789;Bedrock level;Survival;1;19132;19133;";
        byte[] pong = BedrockTargetProbe.testPong(advertisement);
        BedrockTargetProbe.Result result = BedrockTargetProbe.parsePong(pong, pong.length);
        require("MCPE".equals(result.edition()), "target probe edition");
        require("NovaCraft".equals(result.motd()), "target probe MOTD");
        require(result.protocol() == 2169, "target probe protocol");
        require("1.26.45".equals(result.version()), "target probe version");
        require(result.players() == 3 && result.maxPlayers() == 20, "target probe player counts");

        boolean rejected = false;
        try { BedrockTargetProbe.parseAdvertisement("MCPE;broken"); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "target probe malformed advertisement rejection");
        System.out.println("[SelfTest] Bedrock target probe parser passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Self-test failed: " + message);
    }

    private BedrockTargetProbeSelfTest() {}
}
