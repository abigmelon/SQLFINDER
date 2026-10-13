package com.sqlfinder.settings;

import com.sqlfinder.auth.api.AuthController;
import com.sqlfinder.auth.config.SecurityConfig;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import com.sqlfinder.chat.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 模型设置接口测试 */

@WebMvcTest({ModelSettingsController.class,DatabaseSettingsController.class,AuthController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class,SecurityTestAccounts.class,ModelSettingsControllerTest.Backend.class})
class ModelSettingsControllerTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Backend {
        @Bean ReadonlyGrantGenerator generator() { return new ReadonlyGrantGenerator(); }
        @Bean DatabaseSettingsService databaseSettings() {
            return new DatabaseSettingsService(new DatabaseConfiguration("127.0.0.1",3306,"mybatis","reader","private-db-password",false,java.util.List.of("tb_shop")),
                    Path.of("target","database-http-test","database.properties"),c -> new DatabaseConnectionTester.Result("reader@localhost",c.catalog(),"READ_ONLY",
                    java.util.List.of("tb_shop"),false,java.util.List.of()));
        }
        @Bean DatabaseDiagnostics database(com.sqlfinder.auth.config.AuthProperties accounts) {
            var properties=new com.sqlfinder.datasource.config.MetadataProperties();
            properties.setCatalog("mybatis"); properties.setAllowedTables(java.util.List.of("tb_shop","tb_shop_type"));
            return new DatabaseDiagnostics(()->null,properties,accounts);
        }
        @Bean ModelSettingsService settings() {
            var p = new LlmProperties(); p.setApiKey("test-private-key");
            return new ModelSettingsService(p,Path.of("target","settings-http-test","runtime.properties"));
        }
        @Bean ModelConnectionTester tester(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new ModelConnectionTester((p,c,b) -> new DeepSeekTransport.Response(200,
                    "{\"data\":[{\"id\":\"deepseek-chat\"}]}"),mapper);
        }
    }
    @Autowired MockMvc http;
    @Autowired ModelSettingsService service;
    private static final String BODY="{\"model\":\"deepseek-chat\",\"apiKey\":\"test-private-key\",\"timeoutSeconds\":30}";
    @BeforeEach void initial() { service.save(new ModelSettingsRequest("deepseek-chat","test-private-key",60,false)); }

    @Test void anonymousCannotReadSettings() throws Exception {
        http.perform(get("/api/settings/model")).andExpect(status().isUnauthorized());
        http.perform(get("/api/settings/access")).andExpect(status().isUnauthorized());
        http.perform(get("/api/settings/database")).andExpect(status().isUnauthorized());
    }
    @Test void ordinaryUserHasNoSettingsPermissionAndCannotWriteWithValidCsrf() throws Exception {
        var before=service.snapshot();
        http.perform(get("/api/settings/access").with(user("reader"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canManageSettings").value(false));
        http.perform(get("/api/settings/model").with(user("reader"))).andExpect(status().isForbidden());
        http.perform(post("/api/settings/model").with(user("reader")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        http.perform(post("/api/settings/model/test").with(user("reader")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        assertSame(before,service.snapshot());
    }
    @Test void adminStillRequiresCsrf() throws Exception {
        var before=service.snapshot();
        http.perform(post("/api/settings/model").with(user("admin").roles("SETTINGS_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
        assertSame(before,service.snapshot());
    }
    @Test void getAndSaveAreUncachedAndNeverReturnKey() throws Exception {
        var response=http.perform(get("/api/settings/model").with(user("admin").roles("SETTINGS_ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.apiKeyConfigured").value(true)).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("test-private-key"));
        http.perform(post("/api/settings/model").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.timeoutSeconds").value(30)).andExpect(jsonPath("$.apiKey").doesNotExist());
    }
    @Test void connectionTestDoesNotPublishCandidate() throws Exception {
        var before=service.snapshot();
        http.perform(post("/api/settings/model/test").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0]").value("deepseek-chat"));
        assertSame(before,service.snapshot());
    }
    @Test void invalidSettingsAreSafeClientErrors() throws Exception {
        http.perform(post("/api/settings/model").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"bad\",\"apiKey\":\"private value\",\"timeoutSeconds\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_MODEL_SETTINGS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    @Test void missingAndMalformedBodiesReturnSafeErrors() throws Exception {
        http.perform(post("/api/settings/model").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{private-key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_JSON"));
    }
    @Test void databaseDiagnosticsAndGrantGenerationAreAdminOnly() throws Exception {
        http.perform(get("/api/settings/database").with(user("reader"))).andExpect(status().isForbidden());
        http.perform(post("/api/settings/database/check").with(user("reader")).with(csrf())).andExpect(status().isForbidden());
        http.perform(post("/api/settings/database/grants").with(user("reader")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
    @Test void databaseWritesRequireCsrfEvenForAdmin() throws Exception {
        http.perform(post("/api/settings/database/check").with(user("admin").roles("SETTINGS_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
    }
    @Test void databaseConfigurationIsUncachedAndHasNoConnectionSecrets() throws Exception {
        http.perform(get("/api/settings/database").with(user("test-reader").roles("SETTINGS_ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.catalog").value("mybatis"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.url").doesNotExist());
    }
    @Test void grantScriptIsReturnedWithoutExecutingAnyDatabaseOperation() throws Exception {
        http.perform(post("/api/settings/database/grants").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"catalog\":\"mybatis\",\"username\":\"reader\",\"host\":\"localhost\",\"tables\":[\"tb_shop\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sql").value(org.hamcrest.Matchers.containsString("GRANT SELECT")));
        http.perform(post("/api/settings/database/check").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("DATASOURCE_CONNECTION_FAILED"));
    }
    private static final String DB_BODY="{\"host\":\"127.0.0.1\",\"port\":3306,\"catalog\":\"mybatis\",\"username\":\"reader\",\"password\":\"candidate-secret\",\"tables\":[\"tb_shop\"]}";
    @Test void candidateAndSaveRequireAdminAndCsrf() throws Exception {
        for (String path:java.util.List.of("/api/settings/database/test","/api/settings/database")) {
            http.perform(post(path).with(user("reader")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DB_BODY)).andExpect(status().isForbidden());
            http.perform(post(path).with(user("admin").roles("SETTINGS_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(DB_BODY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
        }
    }
    @Test void candidateTestAndSaveNeverReturnPasswordAndKeepActiveScope() throws Exception {
        var test=http.perform(post("/api/settings/database/test").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(DB_BODY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.catalog").value("mybatis")).andReturn();
        assertFalse(test.getResponse().getContentAsString().contains("candidate-secret"));
        var saved=http.perform(post("/api/settings/database").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(DB_BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRestart").value(true)).andExpect(jsonPath("$.active.password").doesNotExist())
                .andExpect(jsonPath("$.saved.password").doesNotExist()).andReturn();
        assertFalse(saved.getResponse().getContentAsString().contains("candidate-secret"));
        http.perform(get("/api/settings/database").with(user("test-reader").roles("SETTINGS_ADMIN")))
                .andExpect(jsonPath("$.tables[1]").value("tb_shop_type"));
    }
    @Test void malformedDatabaseInputReturnsFixedClientError() throws Exception {
        http.perform(post("/api/settings/database/test").with(user("admin").roles("SETTINGS_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(DB_BODY.replace("127.0.0.1","evil/host?password=secret")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_DATABASE_SETTINGS"));
    }
}
