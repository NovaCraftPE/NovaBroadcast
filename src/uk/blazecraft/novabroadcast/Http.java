package uk.blazecraft.novabroadcast;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static Response post(String url, String body, Map<String,String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return send(b.build());
    }

    static Response get(String url, Map<String,String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    static Response put(String url, String body, Map<String,String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return send(b.build());
    }

    static Response delete(String url, Map<String,String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .DELETE();
        headers.forEach(b::header);
        return send(b.build());
    }

    private static Response send(HttpRequest req) throws Exception {
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    record Response(int status, String body) {
        boolean ok() { return status >= 200 && status < 300; }
        void requireOk(String action) {
            if (!ok()) throw new IllegalStateException(action + " failed: HTTP " + status + " - " + body);
        }
    }

    private Http() {}
}
