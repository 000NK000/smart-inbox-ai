package com.smartinbox.processor.stocks.ibkr;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounded HTTPS transport. Authentication and tool requests never follow redirects. */
interface IbkrHttp {
    int MAX_BYTES = 2 * 1024 * 1024;
    Reply send(String method, URI uri, Map<String, String> headers, String body);

    record Reply(int status, Map<String, List<String>> headers, String body) {
        String header(String key) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(key))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
        }
    }

    static void validateEndpoint(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || !"api.ibkr.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getRawQuery() != null) {
            throw new IllegalStateException("IBKR returned an unsupported authentication endpoint.");
        }
    }

    final class Live implements IbkrHttp {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override public Reply send(String method, URI uri, Map<String, String> headers, String body) {
            validateEndpoint(uri);
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25));
            headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            var future = client.sendAsync(request.build(), ignored -> new LimitedBody());
            try {
                var response = future.get(30, TimeUnit.SECONDS);
                return new Reply(response.statusCode(), response.headers().map(),
                        new String(response.body(), StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("IBKR request interrupted.");
            } catch (Exception failure) {
                future.cancel(true);
                // Network errors can contain request URLs or tokens. Do not surface them.
                throw new IllegalStateException("IBKR could not be reached. Please retry.");
            }
        }
    }

    final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if ((long) bytes.size() + chunk.remaining() > MAX_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("IBKR response exceeds size limit."));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
