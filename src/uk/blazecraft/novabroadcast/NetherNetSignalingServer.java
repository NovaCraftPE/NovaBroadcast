package uk.blazecraft.novabroadcast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Minimal HTTP signaling endpoint matching Mojang's public NetherNet partner
 * protocol. It deliberately delegates SDP answering to a PeerBackend.
 */
final class NetherNetSignalingServer implements AutoCloseable {
    interface PeerBackend {
        boolean ready();
        String answer(String networkId, String offerSdp) throws Exception;
    }

    private final String host;
    private final int port;
    private final int maxSdpBytes;
    private final PeerBackend backend;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService executor;

    NetherNetSignalingServer(String host, int port, int maxSdpBytes, PeerBackend backend) {
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.maxSdpBytes = maxSdpBytes;
        this.backend = Objects.requireNonNull(backend);
    }

    void start() throws IOException {
        if (running) return;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid signaling port: " + port);
        if (maxSdpBytes < 1024) throw new IllegalArgumentException("nethernet.maxSdpBytes must be at least 1024");

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(host, port));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        executor.submit(this::acceptLoop);
        System.out.println("[NetherNet] Signaling listening on " + host + ":" + port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handle(socket));
            } catch (SocketException e) {
                if (running) System.err.println("[NetherNet] Signaling socket error: " + e.getMessage());
            } catch (IOException e) {
                if (running) System.err.println("[NetherNet] Signaling accept failed: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(15_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Request request = Request.read(in, maxSdpBytes);

            if (request.method.equals("GET") && request.path.equals("/v1/join")) {
                write(out, backend.ready() ? 204 : 503, backend.ready() ? "No Content" : "Service Unavailable",
                        "text/plain; charset=utf-8", new byte[0]);
                return;
            }

            if (request.method.equals("POST") && request.path.startsWith("/v1/join/")) {
                if (!backend.ready()) {
                    writeText(out, 503, "Service Unavailable", "WebRTC peer backend is not ready");
                    return;
                }
                String networkId = URLDecoder.decode(request.path.substring("/v1/join/".length()), StandardCharsets.UTF_8);
                if (networkId.isBlank() || networkId.contains("/")) {
                    writeText(out, 400, "Bad Request", "Invalid NetworkID");
                    return;
                }
                String contentType = request.headers.getOrDefault("content-type", "");
                if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/sdp")) {
                    writeText(out, 415, "Unsupported Media Type", "Expected application/sdp");
                    return;
                }
                String offer = new String(request.body, StandardCharsets.UTF_8);
                if (!looksLikeSdp(offer)) {
                    writeText(out, 400, "Bad Request", "Malformed SDP offer");
                    return;
                }
                try {
                    String answer = backend.answer(networkId, offer);
                    if (answer == null || !looksLikeSdp(answer)) {
                        writeText(out, 502, "Bad Gateway", "Peer backend returned invalid SDP");
                        return;
                    }
                    write(out, 200, "OK", "application/sdp", answer.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    System.err.println("[NetherNet] SDP exchange failed for " + networkId + ": " + e.getMessage());
                    writeText(out, 502, "Bad Gateway", "SDP exchange failed");
                }
                return;
            }

            writeText(out, 404, "Not Found", "Not Found");
        } catch (Exception e) {
            if (running) System.err.println("[NetherNet] Signaling request failed: " + e.getMessage());
        }
    }

    private static boolean looksLikeSdp(String sdp) {
        return sdp != null && sdp.startsWith("v=0") && sdp.contains("m=application") &&
                sdp.contains("UDP/DTLS/SCTP") && sdp.contains("a=fingerprint:");
    }

    private static void writeText(OutputStream out, int status, String reason, String text) throws IOException {
        write(out, status, reason, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(OutputStream out, int status, String reason, String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (executor != null) executor.close();
    }

    private record Request(String method, String path, Map<String,String> headers, byte[] body) {
        static Request read(InputStream in, int maxBodyBytes) throws IOException {
            String requestLine = readLine(in, 8192);
            if (requestLine == null || requestLine.isBlank()) throw new IOException("Missing HTTP request line");
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) throw new IOException("Invalid HTTP request line");

            Map<String,String> headers = new LinkedHashMap<>();
            for (;;) {
                String line = readLine(in, 8192);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Invalid HTTP header");
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }

            int length = 0;
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null) {
                try { length = Integer.parseInt(lengthHeader); }
                catch (NumberFormatException e) { throw new IOException("Invalid Content-Length"); }
            }
            if (length < 0 || length > maxBodyBytes) throw new IOException("Request body too large");
            byte[] body = in.readNBytes(length);
            if (body.length != length) throw new EOFException("Incomplete request body");
            return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1], Map.copyOf(headers), body);
        }

        private static String readLine(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean cr = false;
            for (int n = 0; n <= maxBytes; n++) {
                int b = in.read();
                if (b < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
                if (cr && b == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                }
                line.write(b);
                cr = b == '\r';
            }
            throw new IOException("HTTP line too long");
        }
    }
}
