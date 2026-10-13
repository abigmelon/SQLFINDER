package com.sqlfinder.chat.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.config.LlmProperties;
import com.sqlfinder.chat.model.PromptMessages;
import com.sqlfinder.settings.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 模型客户端测试 */

class DeepSeekLlmClientTest {
    @TempDir Path directory;
    private ModelSettingsService settings() {
        var p = new LlmProperties(); p.setApiKey("old-key"); return new ModelSettingsService(p,directory.resolve("runtime.properties"));
    }
    private static final String RESPONSE = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"SQL body\"}}]}";
    @Test void subsequentRequestsUseSavedConfigurationAndInflightRequestKeepsOwnSnapshot() {
        var settings = settings(); var mapper = new ObjectMapper();
        DeepSeekTransport transport = (path,config,body) -> {
            assertEquals("/chat/completions",path);
            var json = mapper.readTree(body);
            assertEquals(config.model(),json.path("model").asText());
            if (config.apiKey().equals("old-key")) {
                settings.save(new ModelSettingsRequest("deepseek-chat","new-key",25,false));
                assertEquals(60,config.timeoutSeconds()); assertEquals("deepseek-flash",config.model());
            } else { assertEquals("new-key",config.apiKey()); assertEquals(25,config.timeoutSeconds()); }
            return new DeepSeekTransport.Response(200,RESPONSE);
        };
        var client = new DeepSeekLlmClient(settings,mapper,transport);
        assertEquals("SQL body",client.generateSql(new PromptMessages("system","user")));
        assertEquals("SQL body",client.generateSql(new PromptMessages("system","user")));
    }
    @ParameterizedTest @ValueSource(strings={"null","{}","not-json","{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}"})
    void incompleteOrMalformedResponsesFail(String body) {
        var client = new DeepSeekLlmClient(settings(),new ObjectMapper(),(p,c,b) -> new DeepSeekTransport.Response(200,body));
        assertThrows(IllegalStateException.class, () -> client.generateSql(new PromptMessages("system","user")));
    }
    @Test void upstreamBodyIsNotIncludedInStatusError() {
        var client = new DeepSeekLlmClient(settings(),new ObjectMapper(),(p,c,b) -> new DeepSeekTransport.Response(401,"old-key"));
        assertFalse(assertThrows(IllegalStateException.class, () -> client.generateSql(new PromptMessages("s","u"))).getMessage().contains("old-key"));
    }
    @Test void clearedKeyBlocksGenerationBeforeNetwork() {
        var settings = settings(); settings.save(new ModelSettingsRequest("deepseek-chat","",30,true));
        var client = new DeepSeekLlmClient(settings,new ObjectMapper(),(p,c,b) -> { fail("network forbidden"); return null; });
        assertThrows(IllegalStateException.class, () -> client.generateSql(new PromptMessages("s","u")));
    }
}
