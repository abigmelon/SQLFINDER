package com.sqlfinder.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.ChatController;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.common.api.GlobalExceptionHandler;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.auth.support.TestTableAuthorization;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 问数流程测试 */
class QuestionSqlValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MetadataService metadata = () -> List.of(new TableSchema("mybatis", "tb_shop", "", List.of()));

    private QueryExecutor mustNotExecute() {
        return (userId, candidate) -> { throw new AssertionError("此分支不应调用查询执行器"); };
    }

    private ApplicationContextRunner chat(String modelJson) {
        return chat(modelJson, false, mustNotExecute());
    }

    private ApplicationContextRunner chat(String modelJson, boolean enabled, QueryExecutor executor) {
        return chat(modelJson, new QueryExecutionProperties(enabled, 5, 2), executor);
    }

    private ApplicationContextRunner chat(String modelJson, QueryExecutionProperties properties, QueryExecutor executor) {
        // 仅注册本流程依赖；兼容接入前后的构造器，不启动 JDBC 或模型客户端。
        return new ApplicationContextRunner()
                .withBean(UserMetadataService.class, () -> TestTableAuthorization.forMetadata(metadata))
                .withBean(PromptBuilder.class, () -> new PromptBuilder(mapper))
                .withBean(LlmClient.class, () -> messages -> modelJson)
                .withBean(ModelOutputParser.class, () -> new ModelOutputParser(mapper))
                .withBean(SqlValidator.class, SqlValidator::new)
                .withBean(QueryExecutionProperties.class, () -> properties)
                .withBean(QueryExecutor.class, () -> executor)
                .withBean(ConversationStore.class, com.sqlfinder.chat.support.ConversationTestStores::create)
                .withBean(SchemaFingerprint.class, () -> new SchemaFingerprint(mapper))
                .withBean(QuestionOrchestrator.class)
                .withBean(ChatController.class);
    }

    private String answer(String sql) {
        return mapper.createObjectNode().put("success", true).put("sql", sql).putNull("message").toString();
    }

    private MockMvc http(ChatController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .defaultRequest(get("/").principal(() -> "test-reader"))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void defaultConfigurationExecutesValidatedQueryAndReturnsDataOverHttp() {
        var defaults = org.springframework.boot.context.properties.bind.Binder
                .get(new org.springframework.mock.env.MockEnvironment())
                .bindOrCreate("sqlfinder.query", QueryExecutionProperties.class);
        AtomicInteger executions = new AtomicInteger();
        QueryExecutor executor = (userId, candidate) -> {
            executions.incrementAndGet();
            assertEquals("test-reader", userId);
            assertEquals(List.of("mybatis.tb_shop"), candidate.validation().tables());
            return new QueryResult(List.of("shop_count"), List.of(Map.of("shop_count", 14L)), false);
        };
        chat(answer("SELECT COUNT(*) AS shop_count FROM tb_shop"), defaults, executor).run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = http(context.getBean(ChatController.class));
            mvc.perform(get("/api/chat/sql-policy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionEnabled").value(true));
            mvc.perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"统计商铺数\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("QUERY_SUCCEEDED"))
                    .andExpect(jsonPath("$.columns[0]").value("shop_count"))
                    .andExpect(jsonPath("$.rows[0].shop_count").value(14));
            assertEquals(1, executions.get());
        });
    }

    @Test
    void successfulGenerationMustPassValidationAndDoesNotProduceQueryRows() {
        chat(answer("SELECT COUNT(*) FROM tb_shop")).run(context -> {
            assertThat(context).hasNotFailed();
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("统计商铺数", null));
            assertEquals("SQL_VALIDATED", response.status());
            assertEquals(List.of("mybatis.tb_shop"), response.validation().tables());
            assertEquals(List.of("COUNT"), response.validation().functions());
            assertTrue(response.rows().isEmpty());
            assertTrue(response.columns().isEmpty());
            assertNull(response.errorCode());
        });
    }

    @Test
    void rejectedSqlReturnsReasonAndCannotAppearAsAnApprovedCandidate() {
        chat(answer("SELECT SLEEP(1) FROM tb_shop")).run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("测试", null));
            assertEquals("SQL_REJECTED", response.status());
            assertEquals("SQL_FUNCTION_NOT_ALLOWED", response.errorCode());
            assertNull(response.sql());
            assertNull(response.validation());
            assertTrue(response.message().contains("SLEEP"));
            assertTrue(response.rows().isEmpty());
        });
    }

    @Test
    void clarificationIsNotPassedToSqlValidation() {
        chat("{\"success\":false,\"sql\":null,\"message\":\"请补充统计范围\"}").run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("统计", null));
            assertEquals("NEEDS_CLARIFICATION", response.status());
            assertNull(response.sql());
            assertNull(response.validation());
            assertEquals("请补充统计范围", response.message());
        });
    }

    @Test
    void httpResponseExposesFunctionsAndPolicyWithoutExternalCalls() {
        chat(answer("SELECT COUNT(*) FROM tb_shop")).run(context -> {
            MockMvc mvc = http(context.getBean(ChatController.class));
            mvc.perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"统计商铺数\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SQL_VALIDATED"))
                    .andExpect(jsonPath("$.validation.functions[0]").value("COUNT"))
                    .andExpect(jsonPath("$.validation.tables[0]").value("mybatis.tb_shop"))
                    .andExpect(jsonPath("$.rows").isEmpty());
            mvc.perform(get("/api/chat/sql-policy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(SqlPolicy.VERSION))
                    .andExpect(jsonPath("$.executionEnabled").value(false))
                    .andExpect(jsonPath("$.allowedFunctions").isArray());
        });
    }

    @Test
    void httpRejectionIsAStructuredDomainResult() {
        chat(answer("SELECT * FROM mysql.user")).run(context -> {
            http(context.getBean(ChatController.class))
                    .perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"测试权限\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SQL_REJECTED"))
                    .andExpect(jsonPath("$.errorCode").value("SQL_UNAUTHORIZED_TABLE"))
                    .andExpect(jsonPath("$.sql").doesNotExist());
        });
    }

    @Test
    void promptRulesUseTheSameFunctionPolicyAsTheValidator() {
        var messages = new PromptBuilder(mapper).build("统计", metadata.getAuthorizedSchemas());
        for (String name : SqlPolicy.allowedFunctions()) {
            assertTrue(messages.systemMessage().contains(name));
        }
        assertFalse(SqlPolicy.ALLOWED_FUNCTIONS.contains("SLEEP"));
        assertFalse(SqlPolicy.ALLOWED_FUNCTIONS.contains("DATABASE"));
    }

    @Test
    void enabledExecutionReturnsRowsAndTheValidatedCandidate() {
        AtomicInteger executions = new AtomicInteger();
        String sql = "SELECT COUNT(*) AS shop_count FROM tb_shop";
        QueryExecutor executor = (userId, candidate) -> {
            executions.incrementAndGet();
            assertEquals("local-user", userId);
            assertEquals(sql, candidate.sql());
            assertEquals(List.of("mybatis.tb_shop"), candidate.validation().tables());
            return new QueryResult(List.of("shop_count"), List.of(Map.of("shop_count", 21L)), false);
        };
        chat(answer(sql), true, executor).run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("统计商铺数", null));
            assertEquals("QUERY_SUCCEEDED", response.status());
            assertEquals(List.of("shop_count"), response.columns());
            assertEquals(21L, response.rows().get(0).get("shop_count"));
            assertEquals(sql, response.sql());
            assertEquals(List.of("COUNT"), response.validation().functions());
            assertNull(response.errorCode());
            var truncated = mapper.valueToTree(response).get("truncated");
            assertNotNull(truncated);
            assertFalse(truncated.booleanValue());
            assertEquals(1, executions.get());
        });
    }

    @Test
    void httpSuccessDisplaysTruncatedResults() {
        QueryExecutor executor = (userId, candidate) -> new QueryResult(
                List.of("id"), List.of(Map.of("id", 1), Map.of("id", 2)), true);
        chat(answer("SELECT id FROM tb_shop"), true, executor).run(context -> {
            http(context.getBean(ChatController.class))
                    .perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"列出商铺\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("QUERY_SUCCEEDED"))
                    .andExpect(jsonPath("$.truncated").value(true))
                    .andExpect(jsonPath("$.columns[0]").value("id"))
                    .andExpect(jsonPath("$.rows.length()").value(2))
                    .andExpect(jsonPath("$.rows[1].id").value(2))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("截断")));
        });
    }

    @Test
    void emptyQueryResultStillCountsAsSuccessfulExecution() {
        QueryExecutor executor = (userId, candidate) -> new QueryResult(List.of("id"), List.of(), false);
        chat(answer("SELECT id FROM tb_shop"), true, executor).run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("查询商铺", null));
            assertEquals("QUERY_SUCCEEDED", response.status());
            assertEquals(List.of("id"), response.columns());
            assertTrue(response.rows().isEmpty());
        });
    }
    @Test void httpResponseForwardsActualFieldMetadataAndExactNumericStrings() {
        var field=new com.sqlfinder.chat.model.ResultColumn("id","BIGINT UNSIGNED","INTEGER",false,20,0,"number-or-exact-string");
        QueryExecutor executor=(userId,candidate)->new QueryResult(List.of("id"),List.of(Map.of("id","18446744073709551615")),false,List.of(field));
        chat(answer("SELECT id FROM tb_shop"),true,executor).run(context->{
            http(context.getBean(ChatController.class)).perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"查询编号\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].id").value("18446744073709551615"))
                    .andExpect(jsonPath("$.columnMetadata[0].name").value("id"))
                    .andExpect(jsonPath("$.columnMetadata[0].kind").value("INTEGER"))
                    .andExpect(jsonPath("$.columnMetadata[0].precision").value(20));
        });
    }

    @Test
    void queryFailureIsABusinessResultWithNoDriverDetailsOrPartialRows() {
        QueryExecutor executor = (userId, candidate) -> {
            throw new QueryExecutionException("QUERY_TIMEOUT", "查询超时，请缩小查询范围。",
                    new SQLException("internal driver connection detail"));
        };
        String sql = "SELECT id FROM tb_shop";
        chat(answer(sql), true, executor).run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("查询商铺", null));
            assertEquals("QUERY_FAILED", response.status());
            assertEquals("QUERY_TIMEOUT", response.errorCode());
            assertEquals(sql, response.sql());
            assertNotNull(response.validation());
            assertTrue(response.rows().isEmpty());
            assertTrue(response.columns().isEmpty());
            assertEquals("查询超时，请缩小查询范围。", response.message());
            assertFalse(mapper.writeValueAsString(response).contains("internal driver connection detail"));
            http(context.getBean(ChatController.class))
                    .perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"查询商铺\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("QUERY_FAILED"))
                    .andExpect(jsonPath("$.errorCode").value("QUERY_TIMEOUT"))
                    .andExpect(jsonPath("$.truncated").value(false));
        });
    }

    @Test
    void revalidationFailureFromExecutorHidesThePreviouslyApprovedSql() {
        QueryExecutor executor = (userId, candidate) -> {
            throw new SqlValidationException("SQL_UNAUTHORIZED_TABLE", "授权范围已变化。");
        };
        chat(answer("SELECT id FROM tb_shop"), true, executor).run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("查询商铺", null));
            assertEquals("SQL_REJECTED", response.status());
            assertEquals("SQL_UNAUTHORIZED_TABLE", response.errorCode());
            assertNull(response.sql());
            assertNull(response.validation());
            assertTrue(response.rows().isEmpty());
        });
    }

    @Test
    void enabledExecutionStillDoesNotRunRejectedSql() {
        chat(answer("SELECT SLEEP(1) FROM tb_shop"), true, mustNotExecute()).run(context -> {
            var response = context.getBean(QuestionOrchestrator.class)
                    .ask("local-user", new QuestionRequest("测试", null));
            assertEquals("SQL_REJECTED", response.status());
        });
    }

    @Test
    void enabledExecutionStillDoesNotRunClarification() {
        chat("{\"success\":false,\"sql\":null,\"message\":\"请补充范围\"}", true, mustNotExecute())
                .run(context -> {
                    var response = context.getBean(QuestionOrchestrator.class)
                            .ask("local-user", new QuestionRequest("统计", null));
                    assertEquals("NEEDS_CLARIFICATION", response.status());
                });
    }

    @Test
    void policyEndpointReflectsEnabledExecution() {
        chat(answer("SELECT id FROM tb_shop"), true, mustNotExecute()).run(context -> {
            http(context.getBean(ChatController.class)).perform(get("/api/chat/sql-policy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionEnabled").value(true));
        });
    }

    @Test
    void policyEndpointDisplaysConfiguredExecutionLimits() {
        chat(answer("SELECT id FROM tb_shop")).run(context -> {
            http(context.getBean(ChatController.class)).perform(get("/api/chat/sql-policy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionEnabled").value(false))
                    .andExpect(jsonPath("$.queryTimeoutSeconds").value(5))
                    .andExpect(jsonPath("$.maxResultRows").value(2));
        });
    }

    @Test
    void compatibilityConstructorsDefaultTruncatedToFalse() {
        AnswerResponse sixArguments = new AnswerResponse("SQL_VALIDATED", "测试", null,
                List.of(), List.of(), "尚未执行");
        AnswerResponse eightArguments = new AnswerResponse("SQL_VALIDATED", "测试", null,
                List.of(), List.of(), "尚未执行", null, null);
        for (AnswerResponse response : List.of(sixArguments, eightArguments)) {
            var truncated = mapper.valueToTree(response).get("truncated");
            assertNotNull(truncated, "响应应包含布尔类型的 truncated 字段");
            assertTrue(truncated.isBoolean());
            assertFalse(truncated.booleanValue());
        }
    }
}
