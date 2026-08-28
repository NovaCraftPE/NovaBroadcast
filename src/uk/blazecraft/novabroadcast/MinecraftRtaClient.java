package uk.blazecraft.novabroadcast;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Minimal Xbox RTA client used to obtain the MPSD connection id. */
final class MinecraftRtaClient implements WebSocket.Listener, AutoCloseable {
    private static final URI ENDPOINT = URI.create("wss://rta.xboxlive.com/connect");

    private final CompletableFuture<String> connectionId = new CompletableFuture<>();
    private final StringBuilder text = new StringBuilder();
    private WebSocket socket;

    String connect(String authorizationHeader) throws Exception {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("RTA authorization header is blank.");
        }

        socket = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .newWebSocketBuilder()
                .header("Authorization", authorizationHeader)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(ENDPOINT, this)
                .get(15, TimeUnit.SECONDS);

        return connectionId.get(15, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
        webSocket.sendText("[1,1,\"https://sessiondirectory.xboxlive.com/connections/\"]", true);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            String message = text.toString();
            text.setLength(0);
            tryParseConnectionId(message);
        }
        webSocket.request(1);
        return null;
    }

    private void tryParseConnectionId(String message) {
        try {
            Object parsed = Json.parse(message);
            if (!(parsed instanceof List<?> parts) || parts.size() < 5) return;
            if (!(parts.get(0) instanceof Number type) || type.intValue() != 1) return;
            Object payload = parts.get(4);
            if (!(payload instanceof Map<?,?> map)) return;
            Object id = map.get("ConnectionId");
            if (id != null && !String.valueOf(id).isBlank()) {
                connectionId.complete(String.valueOf(id));
            }
        } catch (RuntimeException ignored) {
            // Ignore unrelated RTA messages.
        }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connectionId.completeExceptionally(error);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!connectionId.isDone()) {
            connectionId.completeExceptionally(new IllegalStateException(
                    "RTA closed before a connection id was received: " + statusCode + " " + reason));
        }
        return null;
    }

    @Override
    public void close() {
        if (socket != null) {
            try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS); }
            catch (Exception ignored) { socket.abort(); }
            socket = null;
        }
    }
}
