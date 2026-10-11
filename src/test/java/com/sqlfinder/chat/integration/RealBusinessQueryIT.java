package com.sqlfinder.chat.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.SqlFinderApplication;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import com.sqlfinder.auth.support.AuthTestHttpClient;
import org.springframework.context.annotation.Import;
import com.sqlfinder.datasource.application.MetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实业务验收：授权表结构、分类统计与关联结果的数据库参考对照。
 * 默认测试不执行，需显式 -Dtest=RealBusinessQueryIT -Dsqlfinder.live-query=true。
 */
@EnabledIfSystemProperty(named = "sqlfinder.live-query", matches = "true")
@ActiveProfiles("local")
@Import(SecurityTestAccounts.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = SqlFinderApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sqlfinder.query.enabled=true",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.datasource.hikari.minimum-idle=1",
                "spring.main.banner-mode=off"})
class RealBusinessQueryIT {
    @Autowired DataSource dataSource;
    @Autowired MetadataService metadataService;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private boolean expectedReadOnly;
    private boolean expectedAutoCommit;

    @BeforeEach
    void verifyCurrentReadOnlyAccount() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            expectedReadOnly = connection.isReadOnly();
            expectedAutoCommit = connection.getAutoCommit();
            assertTrue(expectedReadOnly, "真实验收使用项目的只读连接池配置。");
            assertTrue(expectedAutoCommit, "新验收开始时连接池应处于默认自动提交状态。");
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (ResultSet identity = statement.executeQuery("SELECT CURRENT_USER(), DATABASE()")) {
                    assertTrue(identity.next());
                    assertEquals("sqlfinder_reader@localhost", identity.getString(1));
                    assertEquals("mybatis", identity.getString(2));
                    System.out.println("BUSINESS_DB_ACCOUNT=" + identity.getString(1));
                }
                try (ResultSet grants = statement.executeQuery("SHOW GRANTS")) {
                    boolean found = false;
                    while (grants.next()) {
                        found = true;
                        String grant = grants.getString(1);
                        assertTrue(grant.matches("(?i)GRANT (USAGE|SELECT) ON .+ TO .+")
                                && !grant.toUpperCase(java.util.Locale.ROOT).contains("WITH GRANT OPTION"));
                    }
                    assertTrue(found);
                }
            }
        }
        assertEquals(List.of(List.of("N:0")), referenceRows("""
                SELECT COUNT(*) FROM mybatis.tb_shop s
                LEFT JOIN mybatis.tb_shop_type t ON s.type_id = t.id
                WHERE t.id IS NULL
                """), "存在找不到类型的商铺，需要先明确统计口径。");
    }

    @Test
    void inspectAuthorizedSchema() throws Exception {
        var schemas = metadataService.getAuthorizedSchemas();
        var shop = schemas.stream().filter(s -> s.tableName().equals("tb_shop")).findFirst().orElseThrow();
        var type = schemas.stream().filter(s -> s.tableName().equals("tb_shop_type")).findFirst().orElseThrow();
        String shopType = shop.columns().stream().filter(c -> c.name().equals("type_id"))
                .findFirst().orElseThrow().dataType();
        String typeKey = type.columns().stream().filter(c -> c.name().equals("id"))
                .findFirst().orElseThrow().dataType();
        assertEquals(typeKey, shopType, "逻辑关联字段类型不一致。");
        try (Connection connection = dataSource.getConnection()) {
            List<Map<String, String>> foreignKeys = new ArrayList<>();
            try (ResultSet keys = connection.getMetaData().getImportedKeys("mybatis", null, "tb_shop")) {
                while (keys.next()) {
                    foreignKeys.add(Map.of("fromTable", keys.getString("FKTABLE_NAME"),
                            "fromColumn", keys.getString("FKCOLUMN_NAME"),
                            "toTable", keys.getString("PKTABLE_NAME"),
                            "toColumn", keys.getString("PKCOLUMN_NAME")));
                }
            }
            System.out.println("BUSINESS_FOREIGN_KEYS=" + mapper.writeValueAsString(foreignKeys));
            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet keys = connection.getMetaData().getPrimaryKeys("mybatis", null, "tb_shop_type")) {
                while (keys.next()) primaryKeys.add(keys.getString("COLUMN_NAME"));
            }
            assertEquals(List.of("id"), primaryKeys, "参考查询依赖类型 ID 是唯一主键。");
        }
        System.out.println("BUSINESS_SCHEMAS=" + mapper.writeValueAsString(schemas));
        List<List<String>> orphanCount = referenceRows("""
                SELECT COUNT(*) FROM mybatis.tb_shop s
                LEFT JOIN mybatis.tb_shop_type t ON s.type_id = t.id
                WHERE t.id IS NULL
                """);
        System.out.println("BUSINESS_ORPHAN_SHOPS=" + orphanCount);
        assertEquals(List.of(List.of("N:0")), orphanCount,
                "存在找不到类型的商铺，需要先明确这些数据的统计口径。");
    }

    @Test
    void groupExistingShopsByTypeId() throws Exception {
        compare("group-by-type",
                "按商铺类型ID统计已有商铺的数量，只列出有商铺的类型。按类型ID升序排列，"
                        + "依次返回类型ID、商铺数量这两列。",
                "SELECT type_id, COUNT(*) FROM mybatis.tb_shop GROUP BY type_id ORDER BY type_id",
                List.of("mybatis.tb_shop"));
    }

    @Test
    void joinTypeNamesAndIncludeEmptyTypes() throws Exception {
        compare("join-all-types",
                "统计每种商铺类型下有多少家商铺，包括没有商铺的类型，其数量显示为0。"
                        + "按类型ID升序排列，依次返回类型ID、类型名称、商铺数量这三列。",
                """
                SELECT t.id, t.name, COUNT(s.id)
                FROM mybatis.tb_shop_type t
                LEFT JOIN mybatis.tb_shop s ON s.type_id = t.id
                GROUP BY t.id, t.name ORDER BY t.id
                """,
                List.of("mybatis.tb_shop", "mybatis.tb_shop_type"));
    }

    @Test
    void countShopsForSpecifiedType() throws Exception {
        compare("filter-type-1", "类型ID为1的商铺有多少家？只返回商铺数量这一列。",
                "SELECT COUNT(*) FROM mybatis.tb_shop WHERE type_id = 1",
                List.of("mybatis.tb_shop"));
    }

    private void compare(String caseId, String question, String referenceSql,
                         List<String> expectedTables) throws Exception {
        List<List<String>> reference = referenceRows(referenceSql);
        HttpClient client = AuthTestHttpClient.login(port, mapper);
        HttpResponse<String> policy = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/chat/sql-policy"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, policy.statusCode());
        assertTrue(mapper.readTree(policy.body()).path("executionEnabled").asBoolean());

        System.out.println("BUSINESS_MODEL_REQUEST=" + caseId);
        HttpResponse<String> response = client.send(AuthTestHttpClient.protect(client, port, mapper, HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/chat/question")))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("question", question)), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "真实业务问数 HTTP 失败：" + caseId);
        JsonNode answer = mapper.readTree(response.body());
        System.out.println("BUSINESS_ANSWER=" + mapper.writeValueAsString(answer));

        // 只保存问题、参考结果和业务响应，不保存数据库或模型配置。
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("caseId", caseId);
        report.put("question", question);
        report.put("referenceSql", referenceSql);
        report.put("referenceRows", reference);
        report.put("answer", answer);
        Path reportPath = Path.of("target", "real-business-results", caseId + ".json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        assertEquals("QUERY_SUCCEEDED", answer.path("status").asText(), caseId);
        assertEquals(question, answer.path("question").asText());
        assertFalse(answer.path("truncated").asBoolean());
        assertTrue(answer.path("errorCode").isNull());
        assertEquals(reference.get(0).size(), answer.path("columns").size(),
                "返回列数与问题要求不符：" + caseId);
        assertEquals(reference, answerRows(answer), "模型结果与参考 SQL 不一致：" + caseId);
        assertEquals(mapper.valueToTree(expectedTables), answer.path("validation").path("tables"));
        assertTrue(answer.path("validation").path("functions").toString().contains("\"COUNT\""));

        // 数据若在模型请求期间变化，不将差异误判为生成错误；本次验收要求前后参考值一致。
        assertEquals(reference, referenceRows(referenceSql), "数据库样例在验收期间发生变化。");
    }

    private List<List<String>> referenceRows(String sql) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(expectedReadOnly, connection.isReadOnly(), "前一查询未恢复原始只读状态。");
            assertEquals(expectedAutoCommit, connection.getAutoCommit(), "前一查询未恢复自动提交状态。");
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(5);
                // 参考结果也受行数限制；超过样例规模时不能静默拿截断结果对照。
                statement.setMaxRows(1001);
                try (ResultSet results = statement.executeQuery()) {
                    int count = results.getMetaData().getColumnCount();
                    while (results.next()) {
                        assertTrue(rows.size() < 1000, "样例参考结果超出本测试的行数范围。");
                        List<String> values = new ArrayList<>();
                        for (int column = 1; column <= count; column++) values.add(canonical(results.getObject(column)));
                        rows.add(List.copyOf(values));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        // 单连接池确保归还后复用同一连接，按验收开始时的状态检查恢复。
        try (Connection reused = dataSource.getConnection()) {
            assertEquals(expectedReadOnly, reused.isReadOnly());
            assertEquals(expectedAutoCommit, reused.getAutoCommit());
        }
        return List.copyOf(rows);
    }

    private List<List<String>> answerRows(JsonNode answer) {
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : answer.path("rows")) {
            List<String> values = new ArrayList<>();
            for (JsonNode column : answer.path("columns")) {
                JsonNode value = row.get(column.asText());
                assertNotNull(value, "行缺少 columns 中声明的列。");
                values.add(value.isNull() ? "NULL" : value.isNumber()
                        ? canonical(value.decimalValue()) : canonical(value.asText()));
            }
            rows.add(List.copyOf(values));
        }
        return List.copyOf(rows);
    }

    private String canonical(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number number) {
            return "N:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return "S:" + value;
    }
}
