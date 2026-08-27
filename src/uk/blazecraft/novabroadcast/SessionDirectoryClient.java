package uk.blazecraft.novabroadcast;

/**
 * Clean-room MPSD boundary.
 *
 * Deliberately contains no copied session templates or implementation from
 * MCXboxBroadcast. The next milestone is to build the exact Minecraft session
 * document and lifecycle against the Xbox Multiplayer Session Directory.
 */
final class SessionDirectoryClient {
    private final XboxIdentity identity;

    SessionDirectoryClient(XboxIdentity identity) {
        this.identity = identity;
    }

    void start(AppConfig config) {
        throw new UnsupportedOperationException(
                "Clean-room MPSD session advertising is not implemented in v0.1. " +
                "Authentication succeeded; do not enable session.enabled yet.");
    }
}
