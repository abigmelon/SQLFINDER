package com.sqlfinder.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.auth.config.SecurityConfig;
import com.sqlfinder.auth.support.AuthTestHttpClient;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import com.sqlfinder.auth.support.SecurityTestBackend;
import com.sqlfinder.auth.support.SecurityTestBackend.RecordingOrchestrator;
import com.sqlfinder.chat.api.ChatController;
import com.sqlfinder.datasource.api.MetadataController;
import com.sqlfinder.system.HealthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.sqlfinder.auth.support.AuthTestHttpClient.*;
import static com.sqlfinder.auth.support.SecurityTestAccounts.USERNAME;
import static org.junit.jupiter.api.Assertions.*;

/** 登录HTTP测试 */
@ActiveProfiles({"test", "local-metadata"})
@SpringBootTest(classes = AuthenticationHttpTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.banner-mode=off", "logging.level.root=WARN"})
class AuthenticationHttpTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, SecurityTestAccounts.class, SecurityTestBackend.class,
            AuthController.class, ChatController.class, MetadataController.class, HealthController.class})
    static class TestApp {}

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired RecordingOrchestrator backend;
    @Autowired ApplicationContext context;
    @BeforeEach void reset() { backend.reset(); }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(port, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String sessionId(HttpClient client) {
        return ((CookieManager) client.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
    }
    @Test void actualCookiesSupportLoginQuestionLogoutAndAnonymousReentry() throws Exception {
        HttpClient client = anonymous();
        var csrf = get(client, "/api/auth/csrf");
        assertEquals(200, csrf.statusCode());
        assertTrue(csrf.headers().firstValue("set-cookie").orElse("").contains("HttpOnly"));
        String oldId = sessionId(client);
        var token = mapper.readTree(csrf.body());
        var loggedIn = client.send(HttpRequest.newBuilder(uri(port, "/api/auth/login"))
                .header(token.path("headerName").asText(), token.path("token").asText())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + USERNAME + "&password=" + SecurityTestAccounts.PASSWORD))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loggedIn.statusCode());
        assertNotEquals(oldId, sessionId(client));
        assertEquals(USERNAME, mapper.readTree(get(client, "/api/auth/me").body()).path("username").asText());
        var response = client.send(protect(client, port, mapper, HttpRequest.newBuilder(uri(port, "/api/chat/question")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"统计商铺数\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(USERNAME, backend.username);
        var logout = client.send(protect(client, port, mapper, HttpRequest.newBuilder(uri(port, "/api/auth/logout")))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().allValues("set-cookie").stream().anyMatch(value -> value.contains("Max-Age=0")));
        assertEquals(401, get(client, "/api/auth/me").statusCode());
        assertEquals(1, backend.calls.get());
    }
    @Test void missingCsrfLogoutKeepsRealSessionAlive() throws Exception {
        HttpClient client = AuthTestHttpClient.login(port, mapper);
        var rejected = client.send(HttpRequest.newBuilder(uri(port, "/api/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, rejected.statusCode());
        assertEquals("CSRF_INVALID", mapper.readTree(rejected.body()).path("errorCode").asText());
        assertEquals(200, get(client, "/api/auth/me").statusCode());
        assertEquals(0, backend.calls.get());
    }
    @Test void anonymousProtectedRoutesReturnJsonWhileLoginAssetsArePublic() throws Exception {
        HttpClient client = anonymous();
        for (String path : new String[]{"/api/auth/me", "/api/chat/sql-policy", "/api/datasource/schema"}) {
            var response = get(client, path);
            assertEquals(401, response.statusCode());
            assertEquals("UNAUTHENTICATED", mapper.readTree(response.body()).path("errorCode").asText());
        }
        for (String path : new String[]{"/", "/login.html", "/auth.js", "/health"}) {
            assertEquals(200, get(client, path).statusCode());
        }
        assertEquals(0, backend.calls.get());
    }
    @Test void realBootContextUsesOnlyConfiguredAccountsAndProductionBcryptEncoder() {
        assertEquals(1, context.getBeansOfType(UserDetailsService.class).size());
        assertEquals(1, context.getBeansOfType(PasswordEncoder.class).size());
        var accounts = context.getBean(UserDetailsService.class);
        assertTrue(context.getBean(PasswordEncoder.class).matches(SecurityTestAccounts.PASSWORD,
                accounts.loadUserByUsername(USERNAME).getPassword()));
        assertFalse(accounts.loadUserByUsername(SecurityTestAccounts.DISABLED_USERNAME).isEnabled());
        assertThrows(UsernameNotFoundException.class, () -> accounts.loadUserByUsername("user"));
    }
}
