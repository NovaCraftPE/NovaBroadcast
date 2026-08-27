package uk.blazecraft.novabroadcast;

record MicrosoftTokens(String accessToken, String refreshToken, long expiresAtEpochSeconds) {
    boolean expiresSoon() {
        return System.currentTimeMillis() / 1000L + 120 >= expiresAtEpochSeconds;
    }
}
