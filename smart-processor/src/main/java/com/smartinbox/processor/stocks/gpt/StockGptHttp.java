package com.smartinbox.processor.stocks.gpt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

/** No redirects, bounded response memory, and a deadline that includes the streaming body. */
final class StockGptHttp implements StockGptService.Transport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @Override public StockGptService.Reply send(String method, URI uri, Map<String, String> headers,
                                               String body, Duration timeout, int maxBytes) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(builder.build(),
                ignored -> new BoundedBody(maxBytes));
        try {
            HttpResponse<String> result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new StockGptService.Reply(result.statusCode(), result.body());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ChatGPT 请求已取消");
        } catch (Exception e) {
            future.cancel(true);
            throw new IllegalStateException("无法完成 ChatGPT 请求：网络超时、连接中断或响应过大，请稍后重试");
        }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<String> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Response exceeded limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
