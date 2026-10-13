package com.sqlfinder.settings;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.sqlfinder.chat.application.RequestExecution;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 模型请求传输 */
@Service
public class DeepSeekHttpTransport implements DeepSeekTransport {
    private final HttpClient client;
    private final URI origin;
    public DeepSeekHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), URI.create(ModelSettingsService.BASE_URL));
    }
    // 仅供同包测试连接本地 HTTP stub；网页和配置文件不能覆盖地址。
    DeepSeekHttpTransport(HttpClient client, URI origin) { this.client = client; this.origin = origin; }
    @Override public Response send(String path, ModelConfiguration config, String body)
            throws IOException, InterruptedException {
        if (!"/models".equals(path) && !"/chat/completions".equals(path)) throw new IllegalArgumentException("Unsupported endpoint");
        RequestExecution execution = RequestExecution.current();
        if (execution != null) execution.check();
        long timeoutMillis = TimeUnit.SECONDS.toMillis(config.timeoutSeconds());
        boolean budgetLimited = execution != null && execution.remainingMillis() <= timeoutMillis;
        if (execution != null) timeoutMillis = Math.min(timeoutMillis, execution.remainingMillis());
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofMillis(Math.max(1, timeoutMillis)))
                .header("Authorization", "Bearer " + config.apiKey()).header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        var future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        AutoCloseable registration = execution == null ? () -> {} : execution.onCancel(() -> future.cancel(true));
        try {
            HttpResponse<String> result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (execution != null) execution.check();
            return new Response(result.statusCode(), result.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            if (budgetLimited) execution.expire();
            if (execution != null) execution.check();
            throw new HttpTimeoutException("Model request timed out");
        } catch (CancellationException e) {
            if (execution != null) execution.check();
            throw new IOException("Model request cancelled", e);
        } catch (ExecutionException e) {
            if (budgetLimited && e.getCause() instanceof HttpTimeoutException) execution.expire();
            if (execution != null) execution.check();
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Model request failed", cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            if (execution != null) execution.check();
            throw e;
        } finally {
            // 注销只移除回调，不能影响原始请求异常。
            try { registration.close(); } catch (Exception ignored) { }
        }
    }
}
