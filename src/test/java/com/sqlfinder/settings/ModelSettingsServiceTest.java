package com.sqlfinder.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 模型设置管理测试 */

class ModelSettingsServiceTest {
    @TempDir Path directory;
    private LlmProperties properties() {
        var properties = new LlmProperties(); properties.setApiKey("startup-test-key"); return properties;
    }
    private ModelSettingsService service() { return new ModelSettingsService(properties(), directory.resolve("runtime.properties")); }
    private ModelSettingsRequest request(String key) { return new ModelSettingsRequest("deepseek-chat", key, 30, false); }

    @Test void savePublishesNewSnapshotAndSurvivesRestartWithPriorityOverStartup() {
        var service = service(); var before = service.snapshot();
        service.save(request("new-test-key"));
        assertEquals("startup-test-key", before.apiKey());
        assertEquals("new-test-key", service.snapshot().apiKey());
        assertEquals(30, service.snapshot().timeoutSeconds());
        var restarted = service();
        assertEquals(service.snapshot(), restarted.snapshot());
        assertEquals("saved", restarted.view().source());
    }
    @Test void blankKeyPreservesAndExplicitClearPersistsAcrossRestart() {
        var service = service();
        service.save(request(""));
        assertEquals("startup-test-key", service.snapshot().apiKey());
        service.save(new ModelSettingsRequest("deepseek-chat", "", 30, true));
        assertFalse(service.view().apiKeyConfigured());
        assertEquals("", service().snapshot().apiKey());
    }
    @Test void secretsNeverAppearInViewsOrToString() throws Exception {
        var service = service(); service.save(request("private-test-key"));
        assertFalse(new ObjectMapper().writeValueAsString(service.view()).contains("private-test-key"));
        assertFalse(service.snapshot().toString().contains("private-test-key"));
        assertFalse(request("private-test-key").toString().contains("private-test-key"));
    }
    @ParameterizedTest @ValueSource(strings={"x y", "key\nextra=secret", "key\r", "中文", " ", "a\t"})
    void invalidKeyCannotChangeSnapshotOrCreateFile(String key) {
        var service = service(); var before = service.snapshot();
        var error = assertThrows(ModelSettingsException.class, () -> service.save(request(key)));
        assertEquals(400, error.status()); assertFalse(error.getMessage().contains("secret"));
        assertSame(before, service.snapshot());
        assertFalse(Files.exists(directory.resolve("runtime.properties")));
    }
    @ParameterizedTest @ValueSource(ints={-1,0,4,91,10000})
    void invalidTimeoutRejected(int timeout) {
        assertThrows(ModelSettingsException.class, () -> service().save(new ModelSettingsRequest("deepseek-chat", "", timeout, false)));
    }
    @Test void invalidModelAndConflictingKeyActionsRejected() {
        var service = service();
        assertThrows(ModelSettingsException.class, () -> service.save(new ModelSettingsRequest("other-provider", "", 60, false)));
        assertThrows(ModelSettingsException.class, () -> service.save(new ModelSettingsRequest("deepseek-chat", "key", 60, true)));
        assertThrows(ModelSettingsException.class, () -> service.save(new ModelSettingsRequest("deepseek-chat", "", null, false)));
        assertThrows(ModelSettingsException.class, () -> service.save(request("a".repeat(513))));
    }
    @Test void writeFailureLeavesPreviousConfigurationUntouched() throws Exception {
        Path blocker = directory.resolve("blocked"); Files.writeString(blocker, "blocker");
        var service = new ModelSettingsService(properties(), blocker.resolve("runtime.properties"));
        var before = service.snapshot();
        assertEquals("SETTINGS_SAVE_FAILED", assertThrows(ModelSettingsException.class,
                () -> service.save(request("new-key"))).code());
        assertSame(before, service.snapshot());
        assertEquals("blocker", Files.readString(blocker));
    }
    @Test void corruptSavedFileFailsClosedWithoutPrintingContent() throws Exception {
        Files.writeString(directory.resolve("runtime.properties"), "api-key=private-test-key\nmodel=bad\ntimeout-seconds=abc");
        var error = assertThrows(IllegalStateException.class, this::service);
        assertFalse(error.getMessage().contains("private-test-key")); assertNull(error.getCause());
    }
    @Test void customOriginIsRejectedBeforeAnyNetworkRequest() {
        var properties = properties(); properties.setBaseUrl("https://example.com");
        assertThrows(IllegalStateException.class, () -> new ModelSettingsService(properties, directory.resolve("runtime.properties")));
    }
    @Test void candidateIsValidatedButNotSaved() {
        var service = service();
        assertEquals("candidate", service.candidate(request("candidate")).apiKey());
        assertEquals("startup-test-key", service.snapshot().apiKey());
        assertFalse(Files.exists(directory.resolve("runtime.properties")));
    }
}
