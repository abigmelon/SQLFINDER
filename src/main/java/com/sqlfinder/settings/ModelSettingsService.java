package com.sqlfinder.settings;

import com.sqlfinder.chat.config.LlmProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** 模型设置管理 */
@Service
public class ModelSettingsService {
    public static final String BASE_URL = "https://api.deepseek.com";
    private final Path file;
    private volatile ModelConfiguration current;
    private boolean saved;

    @Autowired
    public ModelSettingsService(LlmProperties properties) {
        this(properties, Path.of("config", "runtime.properties"));
    }

    public ModelSettingsService(LlmProperties properties, Path file) {
        this.file = file.toAbsolutePath().normalize();
        current = validate(properties.getModel(), properties.getApiKey(), properties.getTimeoutSeconds());
        if (!BASE_URL.equals(properties.getBaseUrl().replaceAll("/+$", ""))) {
            throw new IllegalStateException("当前仅支持 DeepSeek 官方接口。");
        }
        if (Files.exists(this.file)) {
            try (var reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
                Properties values = new Properties();
                values.load(reader);
                current = validate(values.getProperty("model"), values.getProperty("api-key"),
                        Integer.parseInt(values.getProperty("timeout-seconds")));
                saved = true;
            } catch (IOException | IllegalArgumentException | ModelSettingsException ex) {
                throw new IllegalStateException("已保存的模型配置无法读取，请检查 config/runtime.properties。");
            }
        }
    }

    public ModelConfiguration snapshot() { return current; }
    public synchronized View view() {
        return new View("DeepSeek", BASE_URL, current.model(), !current.apiKey().isEmpty(),
                current.timeoutSeconds(), saved ? "saved" : "startup");
    }

    public synchronized ModelConfiguration candidate(ModelSettingsRequest request) {
        if (request == null) throw invalid();
        if (request.clearApiKey() && request.apiKey() != null && !request.apiKey().isEmpty()) throw invalid();
        String key = request.clearApiKey() ? "" : request.apiKey() == null || request.apiKey().isEmpty()
                ? current.apiKey() : request.apiKey();
        return validate(request.model(), key, request.timeoutSeconds());
    }

    public synchronized View save(ModelSettingsRequest request) {
        ModelConfiguration next = candidate(request);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".model-", ".tmp");
            // Properties escaping preserves exact values without interpreting newlines as extra keys.
            Properties values = new Properties();
            values.setProperty("model", next.model());
            values.setProperty("api-key", next.apiKey());
            values.setProperty("timeout-seconds", Integer.toString(next.timeoutSeconds()));
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "SQLFINDER private model settings; do not publish");
            }
            // Do not fall back to a non-atomic replacement: failure keeps the old file and snapshot.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            current = next;
            saved = true;
            return view();
        } catch (IOException ex) {
            throw new ModelSettingsException(503, "SETTINGS_SAVE_FAILED", "配置未保存，请检查 config 目录的文件权限后重试。");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static ModelConfiguration validate(String model, String key, Integer timeout) {
        if (model == null || !model.matches("deepseek-[a-z0-9._-]{1,55}")
                || timeout == null || timeout < 5 || timeout > 90) throw invalid();
        if (key == null) key = "";
        if (!key.isEmpty() && !key.matches("[!-~]{1,512}")) throw invalid();
        return new ModelConfiguration(model, key, timeout);
    }
    private static ModelSettingsException invalid() {
        return new ModelSettingsException(400, "INVALID_MODEL_SETTINGS", "请检查模型名称、API key 和超时（5～90 秒）；密钥不能包含空格或换行。");
    }
    public record View(String provider, String baseUrl, String model, boolean apiKeyConfigured,
                       int timeoutSeconds, String source) { }
}
