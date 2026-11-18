package de.notenfuchs.web;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Form-encoded requests against the running test app, shared by the HTTP-level ITs. Redirects are
 * not followed, so tests can assert on them. Takes a supplier because {@code @TestHTTPResource}
 * fields are injected after field initializers run.
 */
public final class TestHttp {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Supplier<URL> rootUrl;

    public TestHttp(Supplier<URL> rootUrl) {
        this.rootUrl = rootUrl;
    }

    public HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        return send("POST", path, encode(form));
    }

    /** For bodies a {@link Map} can't express, e.g. a repeated field ({@code a=1&a=2}). */
    public HttpResponse<String> post(String path, String rawFormBody) throws Exception {
        return send("POST", path, rawFormBody);
    }

    public HttpResponse<String> patch(String path, Map<String, String> form) throws Exception {
        return send("PATCH", path, encode(form));
    }

    public HttpResponse<String> delete(String path) throws Exception {
        return client.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        String base = rootUrl.get().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return HttpRequest.newBuilder().uri(URI.create(base + path));
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
