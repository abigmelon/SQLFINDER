package com.sqlfinder.chat.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.application.LlmClient;
import com.sqlfinder.chat.application.RequestExecution;
import com.sqlfinder.settings.ModelSettingsService;
import com.sqlfinder.settings.DeepSeekTransport;
import com.sqlfinder.chat.model.PromptMessages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 模型调用实现 */

@Service
public class DeepSeekLlmClient implements LlmClient {

    private final ModelSettingsService settings;
    private final ObjectMapper objectMapper;
    private final DeepSeekTransport transport;

    @Autowired
    public DeepSeekLlmClient(
            ModelSettingsService settings,
            ObjectMapper objectMapper,
            DeepSeekTransport transport) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    @Override
    public String generateSql(PromptMessages messages) {
        checkExecution();
        var configuration = settings.snapshot();
        String apiKey = configuration.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型apiKey未配置");
        }

        Map<String, Object> body = Map.of(
                "model", configuration.model(),
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
            var response = transport.send("/chat/completions", configuration, requestBody);
            checkExecution();

            if (response.status() != 200) {
                throw new IllegalStateException("模型服务返回HTTP " + response.status());
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
            checkExecution();
            throw new IllegalStateException("模型请求被中断", e);
        } catch (IOException e) {
            checkExecution();
            throw new IllegalStateException("模型请求或响应解析失败", e);
        }
    }

    private void checkExecution() {
        RequestExecution execution = RequestExecution.current();
        if (execution != null) execution.check();
    }
}
