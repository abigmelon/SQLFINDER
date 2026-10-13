package com.sqlfinder.chat.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.SqlFinderApplication;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import com.sqlfinder.auth.support.AuthTestHttpClient;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实问数验收 */
@EnabledIfSystemProperty(named = "sqlfinder.live-query", matches = "true")
@ActiveProfiles("local")
@Import(SecurityTestAccounts.class)
@SpringBootTest(classes = SqlFinderApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sqlfinder.query.enabled=true",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.datasource.hikari.minimum-idle=1",
                "spring.main.banner-mode=off"})
class RealQueryIT {
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @Test
    void realShopCountMustMatchReadOnlyDatabaseReference() throws Exception {
        long referenceCount;
        boolean originalReadOnly;
        boolean originalAutoCommit;
        try (Connection connection = dataSource.getConnection()) {
            originalReadOnly = connection.isReadOnly();
            originalAutoCommit = connection.getAutoCommit();
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (ResultSet identity = statement.executeQuery(
                        "SELECT CURRENT_USER(), DATABASE()")) {
                    assertTrue(identity.next());
                    String account = identity.getString(1);
                    System.out.println("LIVE_DB_ACCOUNT=" + account);
                    System.out.println("LIVE_DB_CATALOG=" + identity.getString(2));
                    assertFalse(account.startsWith("root@"),
                            "配置实际连接到了 root；应使用现有专用只读账号。");
                    assertEquals("mybatis", identity.getString(2));
                }
                try (ResultSet grants = statement.executeQuery("SHOW GRANTS")) {
                    boolean found = false;
                    while (grants.next()) {
                        found = true;
                        String grant = grants.getString(1);
                        System.out.println("LIVE_DB_GRANT=" + grant);
                        assertTrue(grant.matches("(?i)GRANT (USAGE|SELECT) ON .+ TO .+")
                                        && !grant.toUpperCase(java.util.Locale.ROOT)
                                        .contains("WITH GRANT OPTION"),
                                "账号存在 SELECT/USAGE 以外的授权，需先核对权限。");
                    }
                    assertTrue(found);
                }
                try (ResultSet count = statement.executeQuery(
                        "SELECT COUNT(*) AS shop_count FROM mybatis.tb_shop")) {
                    assertTrue(count.next());
                    referenceCount = count.getLong(1);
                    System.out.println("LIVE_REFERENCE_SHOP_COUNT=" + referenceCount);
                }
            } finally {
                connection.rollback();
            }
        }

        HttpClient client = AuthTestHttpClient.login(port, mapper);
        HttpResponse<String> policyResponse = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/chat/sql-policy"))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, policyResponse.statusCode());
        JsonNode policy = mapper.readTree(policyResponse.body());
        assertTrue(policy.path("executionEnabled").asBoolean());

        System.out.println("LIVE_MODEL_REQUEST_START");
        HttpResponse<String> response = client.send(AuthTestHttpClient.protect(client, port, mapper, HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/chat/question")))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("question", "一共有多少家商铺？"))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "真实问数 HTTP 请求失败。");
        JsonNode answer = mapper.readTree(response.body());
        System.out.println("LIVE_ANSWER=" + mapper.writeValueAsString(answer));
        assertEquals("QUERY_SUCCEEDED", answer.path("status").asText());
        assertEquals("一共有多少家商铺？", answer.path("question").asText());
        assertFalse(answer.path("truncated").asBoolean());
        assertEquals(1, answer.path("columns").size());
        assertEquals(1, answer.path("rows").size());
        String column = answer.path("columns").get(0).asText();
        JsonNode value = answer.path("rows").get(0).get(column);
        assertNotNull(value);
        assertTrue(value.isIntegralNumber(), "商铺数量应为整数。");
        assertEquals(referenceCount, value.longValue(), "模型查询结果与手动计数不一致。");
        assertTrue(answer.path("validation").path("functions")
                .toString().contains("\"COUNT\""));
        assertTrue(answer.path("validation").path("tables")
                .toString().contains("\"mybatis.tb_shop\""));

        // 单连接池确保复用同一连接；恢复状态应与原配置一致，不能假设只读默认为 false。
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(originalAutoCommit, connection.getAutoCommit(), "连接池未恢复自动提交状态。");
            assertEquals(originalReadOnly, connection.isReadOnly(), "连接池未恢复原始只读标记。");
            assertEquals("mybatis", connection.getCatalog());
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (ResultSet count = statement.executeQuery(
                        "SELECT COUNT(*) FROM mybatis.tb_shop")) {
                    assertTrue(count.next());
                    assertEquals(referenceCount, count.getLong(1),
                            "验证期间商铺数据发生变化，需重新对照。");
                }
            }
        }
    }
}
