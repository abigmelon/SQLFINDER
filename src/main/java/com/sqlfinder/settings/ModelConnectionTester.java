package com.sqlfinder.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 模型连接测试 */
@Service
public class ModelConnectionTester {
    private final DeepSeekTransport transport;
    private final ObjectMapper mapper;
    public ModelConnectionTester(DeepSeekTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }
    public Result test(ModelConfiguration configuration) {
        if (configuration.apiKey().isEmpty()) throw new ModelSettingsException(400, "MODEL_KEY_MISSING", "请先填写 API key。");
        try {
            var response = transport.send("/models", new ModelConfiguration(configuration.model(), configuration.apiKey(),
                    Math.min(configuration.timeoutSeconds(), 15)), null);
            if (response.status() == 401) throw new ModelSettingsException(400, "MODEL_KEY_INVALID", "API key 未通过验证，请检查后重试。");
            if (response.status() != 200) throw new ModelSettingsException(502, "MODEL_CONNECTION_FAILED", "DeepSeek 暂时无法提供模型列表，请稍后重试。");
            var root = mapper.readTree(response.body());
            var data = root == null ? null : root.get("data");
            if (data == null || !data.isArray()) throw unavailable();
            List<String> models = new ArrayList<>();
            for (var item : data) {
                var id = item.get("id");
                if (id != null && id.isTextual() && id.asText().matches("deepseek-[a-z0-9._-]{1,55}")) models.add(id.asText());
            }
            if (models.isEmpty()) throw unavailable();
            if (!models.contains(configuration.model())) throw new ModelSettingsException(400, "MODEL_UNAVAILABLE", "连接成功，但所选模型不在可用列表中，请检查模型名称。");
            return new Result("连接成功，所选模型可用。未生成回答，也未保存配置。", List.copyOf(models));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException ex) { throw unavailable(); }
    }
    private static ModelSettingsException unavailable() {
        return new ModelSettingsException(502, "MODEL_CONNECTION_FAILED", "无法读取 DeepSeek 模型列表，请检查网络后重试。");
    }
    public record Result(String message, List<String> models) { }
}
