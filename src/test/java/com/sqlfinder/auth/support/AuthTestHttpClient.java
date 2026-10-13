package com.sqlfinder.auth.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.sqlfinder.auth.support.SecurityTestAccounts.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 认证HTTP测试工具 */
public final class AuthTestHttpClient {
    private AuthTestHttpClient() {}

    public static HttpClient anonymous() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
    }
    public static URI uri(int port, String path) { return URI.create("http://127.0.0.1:" + port + path); }
    public static HttpClient login(int port, ObjectMapper mapper) throws Exception {
        return login(port, mapper, USERNAME, PASSWORD);
    }
    public static HttpClient login(int port, ObjectMapper mapper, String username, String password) throws Exception {
        HttpClient client = anonymous();
        var response = client.send(protect(client, port, mapper, HttpRequest.newBuilder(uri(port, "/api/auth/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                        + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "专用测试账号登录失败");
        assertEquals(username, mapper.readTree(response.body()).path("username").asText());
        return client;
    }
    public static HttpRequest.Builder protect(HttpClient client, int port, ObjectMapper mapper,
                                              HttpRequest.Builder request) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri(port, "/api/auth/csrf"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "读取测试会话 CSRF 失败");
        var token = mapper.readTree(response.body());
        return request.header(token.path("headerName").asText(), token.path("token").asText());
    }
}
