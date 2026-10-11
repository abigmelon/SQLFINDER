package com.sqlfinder.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.system.HealthController;
import com.sqlfinder.auth.config.SecurityConfig;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC 注册验收：自动发现全局处理器、正常接口及不存在资源的 HTTP 状态。
 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, SecurityTestAccounts.class})
class GlobalExceptionHandlerRegistrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;

    @Test
    void normalHealthResponseRemains200() throws Exception {
        http.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @WithMockUser
    void missingResourceIsStructured404RatherThan500() throws Exception {
        var response = http.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn();
        var body = mapper.readTree(response.getResponse().getContentAsString());
        assertEquals(3, body.size());
        assertDoesNotThrow(() -> UUID.fromString(body.path("requestId").asText()));
    }
}
