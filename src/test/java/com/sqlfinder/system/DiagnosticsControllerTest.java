package com.sqlfinder.system;

import com.sqlfinder.auth.config.SecurityConfig;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 诊断鉴权测试 */
@WebMvcTest(DiagnosticsController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, SecurityTestAccounts.class, DiagnosticsControllerTest.Backend.class})
class DiagnosticsControllerTest {
    @Autowired MockMvc http;
    @Autowired StartupDiagnosticsTest.History history;
    @TestConfiguration(proxyBeanMethods = false)
    static class Backend {
        @Bean StartupDiagnosticsTest.History history() { return new StartupDiagnosticsTest.History(); }
        @Bean StartupDiagnostics diagnostics(StartupDiagnosticsTest.History history) {
            return new StartupDiagnostics(() -> new com.sqlfinder.settings.ModelSettingsService.View("DeepSeek", "https://api.deepseek.com", "deepseek-chat", true, 30, "saved"),
                    user -> new com.sqlfinder.settings.DatabaseDiagnostics.Result("account", "catalog", "READ_ONLY",
                            List.of(new com.sqlfinder.settings.DatabaseDiagnostics.TableCheck("film", true, true)), 1), history);
        }
    }
    @Test void anonymousCannotTriggerDiagnostics() throws Exception {
        http.perform(get("/api/system/diagnostics")).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedUserChecksOwnAccessWithoutCaching() throws Exception {
        http.perform(get("/api/system/diagnostics").with(user("reader"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("READY"));
        org.junit.jupiter.api.Assertions.assertEquals("reader", history.checkedUser);
    }
}
