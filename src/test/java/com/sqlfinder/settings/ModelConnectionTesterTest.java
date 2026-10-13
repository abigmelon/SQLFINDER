package com.sqlfinder.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** 模型连接测试验证 */

class ModelConnectionTesterTest {
    private final ModelConfiguration configuration = new ModelConfiguration("deepseek-chat", "private-key", 60);
    private ModelConnectionTester tester(int status, String body) {
        return new ModelConnectionTester((path, config, payload) -> {
            assertEquals("/models", path); assertNull(payload);
            assertEquals("private-key", config.apiKey()); assertEquals(15, config.timeoutSeconds());
            return new DeepSeekTransport.Response(status, body);
        }, new ObjectMapper());
    }
    @Test void listsActualModelsWithoutGeneration() {
        var result = tester(200, "{\"data\":[{\"id\":\"deepseek-chat\"},{\"id\":\"deepseek-reasoner\"}]}").test(configuration);
        assertEquals(2, result.models().size()); assertFalse(result.toString().contains("private-key"));
    }
    @Test void missingKeyNeverCallsTransport() {
        var tester = new ModelConnectionTester((path,c,b) -> { fail("must not call network"); return null; }, new ObjectMapper());
        assertEquals("MODEL_KEY_MISSING", assertThrows(ModelSettingsException.class,
                () -> tester.test(new ModelConfiguration("deepseek-chat", "", 60))).code());
    }
    @Test void unavailableSelectedModelIsDistinguishedFromConnectionFailure() {
        assertEquals("MODEL_UNAVAILABLE", assertThrows(ModelSettingsException.class,
                () -> tester(200, "{\"data\":[{\"id\":\"deepseek-reasoner\"}]}").test(configuration)).code());
    }
    @ParameterizedTest @ValueSource(ints={301,401,403,429,500,503})
    void upstreamFailuresNeverExposeResponseBody(int status) {
        var error = assertThrows(ModelSettingsException.class, () -> tester(status, "private-key backend-details").test(configuration));
        assertFalse(error.getMessage().contains("private-key"));
        assertEquals(status == 401 ? "MODEL_KEY_INVALID" : "MODEL_CONNECTION_FAILED", error.code());
    }
    @ParameterizedTest @ValueSource(strings={"null", "{}", "[]", "{\"data\":[]}", "not-json", "{\"data\":[{\"id\":\"<script>\"}]}"})
    void malformedOrUnusableModelListIsRejected(String body) {
        assertThrows(ModelSettingsException.class, () -> tester(200, body).test(configuration));
    }
    @Test void networkErrorDoesNotExposeExceptionDetails() {
        var tester = new ModelConnectionTester((p,c,b) -> { throw new IOException("private-key"); }, new ObjectMapper());
        var error = assertThrows(ModelSettingsException.class, () -> tester.test(configuration));
        assertFalse(error.getMessage().contains("private-key")); assertNull(error.getCause());
    }
    @Test void interruptionIsPreserved() {
        var tester = new ModelConnectionTester((p,c,b) -> { throw new InterruptedException("secret"); }, new ObjectMapper());
        try { assertThrows(ModelSettingsException.class, () -> tester.test(configuration)); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
