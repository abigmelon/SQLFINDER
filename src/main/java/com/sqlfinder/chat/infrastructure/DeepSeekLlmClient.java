package com.sqlfinder.chat.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.application.LlmClient;
import com.sqlfinder.chat.config.LlmProperties;
import com.sqlfinder.chat.model.PromptMessages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DeepSeekLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public DeepSeekLlmClient(
            LlmProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String generateSql(PromptMessages messages) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型apiKey未配置");
        }

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", messages.systemMessage()),
                        Map.of("role", "user", "content", messages.userMessage())
                ),
                "stream", false,
                "thinking", Map.of("type", "disabled"),
                "response_format", Map.of("type", "json_object"),
                "max_tokens", 2048
        );

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() != 200) {
                throw new IllegalStateException("模型服务返回HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("模型响应格式异常");
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("模型响应缺少 choices");
            }

            JsonNode firstChoice = choices.get(0);
            String finishReason = firstChoice.path("finish_reason").asText();
            if (!"stop".equals(finishReason)) {
                throw new IllegalStateException(
                        "模型回答未完整结束：" + finishReason);
            }

            JsonNode content = firstChoice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException("模型返回空回答");
            }

            return content.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("模型请求或响应解析失败", e);
        }
    }
}
