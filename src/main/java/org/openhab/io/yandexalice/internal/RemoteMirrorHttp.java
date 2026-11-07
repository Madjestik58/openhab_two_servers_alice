package org.openhab.io.yandexalice.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.json.JSONArray;

/** REST transport to the source openHAB. */
public final class RemoteMirrorHttp {
    private final URI base;
    private final HttpClient client;
    private final String authorization;
    private volatile InputStream eventStream;

    public RemoteMirrorHttp(String remoteUrl, String apiToken) {
        URI uri = URI.create(remoteUrl.strip());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Remote URL must be an http(s) server origin");
        }
        this.base = URI.create(uri.toString().replaceAll("/+$", ""));
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
                .build();
        this.authorization = apiToken.isBlank() ? ""
                : "Basic " + Base64.getEncoder().encodeToString((apiToken + ":").getBytes(StandardCharsets.US_ASCII));
    }

    public JSONArray getItems() throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("/rest/items?recursive=false"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
        authorize(request);
        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("Remote Items HTTP " + response.statusCode());
        return new JSONArray(response.body());
    }

    public void postCommand(String sourceName, String command) throws IOException, InterruptedException {
        String escapedName = URLEncoder.encode(sourceName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("/rest/items/" + escapedName))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(command, StandardCharsets.UTF_8));
        authorize(request);
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Remote command HTTP " + response.statusCode());
        }
    }

    public InputStream openEvents() throws IOException, InterruptedException {
        URI events = base.resolve("/rest/events?topics=openhab/items/*/command,openhab/items/*/state");
        HttpRequest.Builder request = HttpRequest.newBuilder(events).header("Accept", "text/event-stream").GET();
        authorize(request);
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Remote events HTTP " + response.statusCode());
        }
        eventStream = response.body();
        return eventStream;
    }

    public void closeEvents() {
        InputStream current = eventStream;
        eventStream = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing the connection during reconfiguration is best effort.
            }
        }
    }

    private void authorize(HttpRequest.Builder request) {
        if (!authorization.isEmpty())
            request.header("Authorization", authorization);
    }
}
