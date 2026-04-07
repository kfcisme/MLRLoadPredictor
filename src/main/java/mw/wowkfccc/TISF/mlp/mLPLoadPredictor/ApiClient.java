package mw.wowkfccc.TISF.mlp.mLPLoadPredictor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ApiClient {
    private final HttpClient client;
    private final String baseUrl;
    private final int timeoutMs;

    public ApiClient(String baseUrl, int timeoutMs) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        this.timeoutMs = timeoutMs;
    }

    public String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET()
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        return res.body();
    }

    public String postJson(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        return res.body();
    }
}
