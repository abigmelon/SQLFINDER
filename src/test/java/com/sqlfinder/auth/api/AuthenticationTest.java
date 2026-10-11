package com.sqlfinder.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.auth.config.SecurityConfig;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import com.sqlfinder.auth.support.SecurityTestBackend;
import com.sqlfinder.auth.support.SecurityTestBackend.RecordingOrchestrator;
import com.sqlfinder.chat.api.ChatController;
import com.sqlfinder.datasource.api.MetadataController;
import com.sqlfinder.system.HealthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static com.sqlfinder.auth.support.SecurityTestAccounts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 生产过滤器验收：真实 CSRF、登录、会话轮换和退出；不用 csrf() 绕过 token 接口。 */
@WebMvcTest({AuthController.class, ChatController.class, MetadataController.class, HealthController.class})
@ActiveProfiles({"test", "local-metadata"})
@Import({SecurityConfig.class, SecurityTestAccounts.class, SecurityTestBackend.class})
class AuthenticationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @Autowired RecordingOrchestrator backend;
    private static final String QUESTION = "{\"question\":\"统计商铺数\"}";

    private record Token(MockHttpSession session, String header, String value) {
        MockHttpServletRequestBuilder protect(MockHttpServletRequestBuilder request) {
            return request.session(session).header(header, value);
        }
    }
    @BeforeEach void reset() { backend.reset(); }

    private Token token(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = http.perform(request).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("X-CSRF-TOKEN", body.path("headerName").asText());
        assertFalse(body.path("token").asText().isBlank());
        return new Token((MockHttpSession) result.getRequest().getSession(false),
                body.path("headerName").asText(), body.path("token").asText());
    }
    private MockHttpSession login(Token token) throws Exception {
        var result = http.perform(token.protect(post("/api/auth/login"))
                        .param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(USERNAME)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    private MvcResult error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        var result = http.perform(request).andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value(code)).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(3, body.size());
        assertDoesNotThrow(() -> UUID.fromString(body.path("requestId").asText()));
        assertFalse(body.path("message").asText().isBlank());
        assertEquals(0, backend.calls.get(), "拦截的请求不能进入业务编排器");
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/chat/sql-policy", "/api/datasource/schema"})
    void anonymousReadsReturnJson401(String path) throws Exception {
        error(get(path), 401, "UNAUTHENTICATED");
    }
    @Test void errorsRemainJsonEvenWithUnsupportedAccept() throws Exception {
        error(get("/api/auth/me").accept("image/png"), 401, "UNAUTHENTICATED");
    }
    @Test void csrfEndpointCreatesAnonymousSession() throws Exception {
        assertNotNull(token(null).session());
    }
    @Test void loginChangesSessionIdAndEnablesProtectedReads() throws Exception {
        Token before = token(null);
        String oldId = before.session().getId();
        MockHttpSession session = login(before);
        assertNotEquals(oldId, session.getId());
        http.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
        http.perform(get("/api/chat/sql-policy").session(session)).andExpect(status().isOk());
        http.perform(get("/api/datasource/schema").session(session)).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings = {USERNAME, "unknown-user"})
    void wrongCredentialsHaveSameSafeFailure(String username) throws Exception {
        Token csrf = token(null);
        var result = error(csrf.protect(post("/api/auth/login"))
                .param("username", username).param("password", "wrong-private-password"),
                401, "AUTHENTICATION_FAILED");
        assertFalse(result.getResponse().getContentAsString().contains("wrong-private-password"));
        error(get("/api/auth/me").session(csrf.session()), 401, "UNAUTHENTICATED");
    }
    @ParameterizedTest @ValueSource(strings = {"/api/auth/login", "/api/chat/question", "/api/auth/logout"})
    void missingCsrfIs403(String path) throws Exception { error(post(path), 403, "CSRF_INVALID"); }
    @ParameterizedTest @ValueSource(strings = {"/api/auth/login", "/api/chat/question", "/api/auth/logout"})
    void invalidCsrfIs403(String path) throws Exception {
        Token csrf = token(null);
        error(post(path).session(csrf.session()).header(csrf.header(), "invalid-token"), 403, "CSRF_INVALID");
    }
    @Test void anonymousQuestionWithValidCsrfIs401() throws Exception {
        error(token(null).protect(post("/api/chat/question")).contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION), 401, "UNAUTHENTICATED");
    }
    @Test void oldTokenFailsAfterLoginAndFreshTokenForwardsActualUsername() throws Exception {
        Token before = token(null);
        MockHttpSession session = login(before);
        error(before.protect(post("/api/chat/question")).contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION), 403, "CSRF_INVALID");
        Token fresh = token(session);
        http.perform(fresh.protect(post("/api/chat/question")).contentType(MediaType.APPLICATION_JSON)
                        .content(QUESTION)).andExpect(status().isOk());
        assertEquals(USERNAME, backend.username);
        assertEquals(1, backend.calls.get());
    }
    @Test void logoutWithoutCsrfDoesNotDestroySession() throws Exception {
        MockHttpSession session = login(token(null));
        error(post("/api/auth/logout").session(session), 403, "CSRF_INVALID");
        http.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        assertFalse(session.isInvalid());
    }
    @Test void logoutInvalidatesSessionAndDeletesCookie() throws Exception {
        MockHttpSession session = login(token(null));
        http.perform(token(session).protect(post("/api/auth/logout")))
                .andExpect(status().isOk()).andExpect(cookie().maxAge("JSESSIONID", 0));
        assertTrue(session.isInvalid());
        error(get("/api/auth/me"), 401, "UNAUTHENTICATED");
        Token next = token(null);
        assertNotEquals(session.getId(), next.session().getId());
        error(next.protect(post("/api/chat/question")).contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION), 401, "UNAUTHENTICATED");
    }
    @Test void httpBasicCannotBypassSessionLogin() throws Exception {
        String basic = Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        error(get("/api/auth/me").header("Authorization", "Basic " + basic), 401, "UNAUTHENTICATED");
    }
    @Test void disabledAccountCannotLoginEvenWithCorrectPassword() throws Exception {
        Token csrf = token(null);
        error(csrf.protect(post("/api/auth/login")).param("username", DISABLED_USERNAME)
                .param("password", PASSWORD), 401, "AUTHENTICATION_FAILED");
        error(get("/api/auth/me").session(csrf.session()), 401, "UNAUTHENTICATED");
    }
    @Test void secondConfiguredUserIsForwardedToQuestionOrchestrator() throws Exception {
        Token csrf = token(null);
        var result = http.perform(csrf.protect(post("/api/auth/login"))
                        .param("username", SECOND_USERNAME).param("password", SECOND_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(SECOND_USERNAME)).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        http.perform(token(session).protect(post("/api/chat/question")).contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION)).andExpect(status().isOk());
        assertEquals(SECOND_USERNAME, backend.username);
    }
    @Test void springDefaultUserIsNoLongerAValidLoginAccount() throws Exception {
        Token csrf = token(null);
        error(csrf.protect(post("/api/auth/login")).param("username", "user").param("password", PASSWORD),
                401, "AUTHENTICATION_FAILED");
    }
    @Test void loginRejectsPasswordTruncationButAcceptsExact72BytePassword() throws Exception {
        Token csrf = token(null);
        error(csrf.protect(post("/api/auth/login")).param("username", LONG_PASSWORD_USERNAME)
                .param("password", LONG_PASSWORD + "extra-suffix"), 401, "AUTHENTICATION_FAILED");
        http.perform(csrf.protect(post("/api/auth/login")).param("username", LONG_PASSWORD_USERNAME)
                .param("password", LONG_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(LONG_PASSWORD_USERNAME));
    }
}
