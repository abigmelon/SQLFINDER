package com.sqlfinder.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.SqlFinderApplication;
import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.auth.support.SecurityTestAccounts;
import com.sqlfinder.chat.application.LlmClient;
import com.sqlfinder.chat.application.QueryExecutor;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.sqlfinder.chat.model.PromptMessages;
import org.mockito.ArgumentCaptor;
import static com.sqlfinder.auth.support.SecurityTestAccounts.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static com.sqlfinder.auth.support.AuthTestHttpClient.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 主程序启动验收：生产扫描、账号和问数主链路；仅模型、元数据和执行器为替身。 */
@ActiveProfiles({"test", "local-metadata"})
@Import(SecurityTestAccounts.class)
@SpringBootTest(classes = SqlFinderApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "sqlfinder.query.enabled=false", "spring.main.banner-mode=off", "logging.level.root=WARN"})
class AuthenticatedApplicationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @MockitoBean MetadataService metadata;
    @MockitoBean LlmClient model;
    @MockitoBean QueryExecutor executor;

    @BeforeEach void schemasForBothUsers() {
        when(metadata.getAuthorizedSchemas()).thenReturn(List.of(
                new TableSchema("mybatis", "tb_shop", "商铺", List.of()),
                new TableSchema("mybatis", "tb_shop_type", "分类", List.of())));
    }

    private HttpResponse<String> question(java.net.http.HttpClient client, String extraFields) throws Exception {
        return client.send(protect(client, port, mapper, HttpRequest.newBuilder(uri(port, "/api/chat/question")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"统计数量\"" + extraFields + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void twoLoggedInUsersReceiveDifferentSchemasAndPrompts() throws Exception {
        var primary = login(port, mapper);
        var secondary = login(port, mapper, SECOND_USERNAME, SECOND_PASSWORD);
        var first = primary.send(HttpRequest.newBuilder(uri(port, "/api/datasource/schema")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var second = secondary.send(HttpRequest.newBuilder(uri(port, "/api/datasource/schema")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(2, mapper.readTree(first.body()).size());
        assertEquals(1, mapper.readTree(second.body()).size());
        assertEquals("tb_shop_type", mapper.readTree(second.body()).get(0).path("tableName").asText());
        when(model.generateSql(any())).thenReturn("{\"success\":true,\"sql\":\"SELECT COUNT(*) FROM tb_shop_type\",\"message\":null}");
        assertEquals("SQL_VALIDATED", mapper.readTree(question(secondary, "").body()).path("status").asText());
        var captured = ArgumentCaptor.forClass(PromptMessages.class);
        verify(model).generateSql(captured.capture());
        var promptSchemas = mapper.readTree(captured.getValue().userMessage()).path("schema");
        assertEquals(1, promptSchemas.size());
        assertEquals("tb_shop_type", promptSchemas.get(0).path("tableName").asText());
        verifyNoInteractions(executor);
    }

    @ParameterizedTest @ValueSource(strings = {"SELECT COUNT(*) FROM tb_shop", "SELECT * FROM mybatis.tb_shop",
            "SELECT t.id FROM tb_shop_type t JOIN tb_shop s ON t.id = s.type_id",
            "SELECT * FROM tb_shop_type WHERE id IN (SELECT type_id FROM tb_shop)",
            "SELECT * FROM (SELECT * FROM tb_shop) x", "SELECT * FROM other_db.tb_shop_type"})
    void modelCannotAccessTablesOutsideLoggedInUserScope(String sql) throws Exception {
        when(model.generateSql(any())).thenReturn(mapper.createObjectNode().put("success", true).put("sql", sql)
                .putNull("message").toString());
        var secondary = login(port, mapper, SECOND_USERNAME, SECOND_PASSWORD);
        // 伪造请求中的身份和授权也不能扩大 Principal 的服务端授权。
        var response = question(secondary, ",\"userId\":\"test-reader\",\"allowedTables\":[\"mybatis.tb_shop\"]");
        assertEquals(200, response.statusCode());
        var body = mapper.readTree(response.body());
        assertEquals("SQL_REJECTED", body.path("status").asText());
        assertEquals("SQL_UNAUTHORIZED_TABLE", body.path("errorCode").asText());
        assertTrue(body.path("sql").isNull());
        verifyNoInteractions(executor);
    }

    @Test void userWithoutGrantsReceives403BeforeMetadataOrModel() throws Exception {
        var client = login(port, mapper, NO_GRANTS_USERNAME, PASSWORD);
        var schemaResponse = client.send(HttpRequest.newBuilder(uri(port, "/api/datasource/schema")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var questionResponse = question(client, "");
        for (var response : List.of(schemaResponse, questionResponse)) {
            assertEquals(403, response.statusCode());
            var body = mapper.readTree(response.body());
            assertEquals("TABLE_ACCESS_DENIED", body.path("errorCode").asText());
            assertDoesNotThrow(() -> java.util.UUID.fromString(body.path("requestId").asText()));
            assertFalse(response.body().contains("tb_shop"));
        }
        verify(metadata, never()).getAuthorizedSchemas();
        verifyNoInteractions(model, executor);
    }

    @Test void fullApplicationCanLoginAndValidateQuestionWithConfiguredAccount() throws Exception {
        assertEquals(1, context.getBeansOfType(AuthProperties.class).size());
        assertEquals(1, context.getBeansOfType(UserDetailsService.class).size());
        when(metadata.getAuthorizedSchemas()).thenReturn(List.of(new TableSchema("mybatis", "tb_shop", "", List.of())));
        when(model.generateSql(any())).thenReturn("{\"success\":true,\"sql\":\"SELECT COUNT(*) FROM tb_shop\",\"message\":null}");
        var client = login(port, mapper);
        var response = client.send(protect(client, port, mapper, HttpRequest.newBuilder(uri(port, "/api/chat/question")))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"统计商铺数\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var answer = mapper.readTree(response.body());
        assertEquals("SQL_VALIDATED", answer.path("status").asText());
        assertEquals("mybatis.tb_shop", answer.path("validation").path("tables").get(0).asText());
        assertEquals("COUNT", answer.path("validation").path("functions").get(0).asText());
        verify(model, times(1)).generateSql(any());
        verifyNoInteractions(executor);
    }
}
