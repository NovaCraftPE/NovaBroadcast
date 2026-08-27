package uk.blazecraft.novabroadcast;

import java.net.http.WebSocket;

/**
 * Clean-room transport boundary for Minecraft Bedrock NetherNet/WebRTC.
 *
 * No third-party broadcaster code is used here. Future implementation will
 * own signaling, peer lifecycle, and client transfer logic.
 */
final class NetherNetTransport {
    private WebSocket signallingSocket;

    void start() {
        throw new UnsupportedOperationException(
                "NetherNet transport is not implemented in v0.1.");
    }
}
