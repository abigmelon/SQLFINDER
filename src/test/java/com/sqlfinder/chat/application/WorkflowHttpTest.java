package com.sqlfinder.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.auth.support.TestTableAuthorization;
import com.sqlfinder.chat.api.ChatController;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.common.api.GlobalExceptionHandler;
import com.sqlfinder.common.api.RequestIdFilter;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 工作流接口测试 */
class WorkflowHttpTest {
    private static final String GOOD = "{\"success\":true,\"sql\":\"SELECT COUNT(*) AS n FROM tb_shop\",\"message\":null}";
    private static final String QUESTION = "{\"question\":\"统计商铺数\"}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<TableSchema> schemas = List.of(new TableSchema("mybatis", "tb_shop", "", List.of()));
    private final ConversationStore conversations = com.sqlfinder.chat.support.ConversationTestStores.create();
    private final AtomicInteger queries = new AtomicInteger();

    private MockMvc http(WorkflowRegistry registry, LlmClient model) {
        var properties = new QueryExecutionProperties(true, 5, 1000);
        var orchestrator = new QuestionOrchestrator(TestTableAuthorization.forMetadata(() -> schemas),
                new PromptBuilder(mapper), model, new ModelOutputParser(mapper), new SqlValidator(),
                (user, sql) -> { queries.incrementAndGet(); return new QueryResult(List.of("n"), List.of(Map.of("n", 3)), false); },
                properties, conversations, new SchemaFingerprint(mapper));
        var controller = new ChatController(orchestrator, properties);
        controller.workflows(registry);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter()).defaultRequest(get("/").principal(() -> "test-reader")).build();
    }
    @Test void successResponseHistoryAndAuditShareOneRequestId() throws Exception {
        try (var registry = new WorkflowRegistry()) {
            var mvc = http(registry, messages -> GOOD); UUID id = UUID.randomUUID();
            var response = mvc.perform(post("/api/chat/question").header("X-Request-Id", id)
                    .contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                    .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", id.toString()))
                    .andExpect(jsonPath("$.requestId").value(id.toString())).andReturn();
            var answer = mapper.readTree(response.getResponse().getContentAsString());
            var history = conversations.detail("test-reader", UUID.fromString(answer.path("conversationId").asText()),
                    new SchemaFingerprint(mapper).of(schemas), 0);
            assertEquals(id, history.turns().get(0).answer().requestId());
            mvc.perform(get("/api/chat/requests/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("QUERY_SUCCEEDED"))
                    .andExpect(jsonPath("$.stageDurations.MODEL_REQUEST").isNumber())
                    .andExpect(jsonPath("$.stageDurations.EXECUTION").isNumber());
            assertEquals(1, queries.get());
            assertNull(RequestExecution.current());
        }
    }
    @Test void modelFailureUsesSamePublicErrorCodeAndDoesNotPersistPrivateCause() throws Exception {
        try (var registry = new WorkflowRegistry()) {
            var mvc = http(registry, messages -> { throw new IllegalStateException("private-model-token"); });
            UUID id = UUID.randomUUID();
            var response = mvc.perform(post("/api/chat/question").header("X-Request-Id", id)
                    .contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                    .andExpect(status().isBadGateway()).andExpect(header().string("X-Request-Id", id.toString()))
                    .andExpect(jsonPath("$.requestId").value(id.toString())).andReturn();
            assertFalse(response.getResponse().getContentAsString().contains("private-model-token"));
            var run = registry.find(id, "test-reader");
            assertEquals("MODEL_REQUEST_FAILED", run.errorCode());
            assertEquals("MODEL_REQUEST", run.failureStage());
            assertFalse(mapper.writeValueAsString(run).contains("private-model-token"));
            assertEquals(0, queries.get());
            assertNull(RequestExecution.current());
        }
    }
    @Test void rejectedSqlKeepsValidationAsFailureStageAfterSavingItsAnswer() throws Exception {
        try (var registry = new WorkflowRegistry()) {
            var mvc = http(registry, messages -> "{\"success\":true,\"sql\":\"DELETE FROM tb_shop\",\"message\":null}");
            UUID id = UUID.randomUUID();
            mvc.perform(post("/api/chat/question").header("X-Request-Id", id)
                    .contentType(MediaType.APPLICATION_JSON).content(QUESTION)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SQL_REJECTED"));
            var run = registry.find(id, "test-reader");
            assertEquals("VALIDATION", run.failureStage());
            assertEquals("HISTORY", run.stage());
            assertNotNull(run.errorCode());
            assertEquals(0, queries.get());
        }
    }
    private LlmClient blockingModel(CountDownLatch entered) {
        return messages -> {
            var cancelled = new CountDownLatch(1);
            RequestExecution execution = RequestExecution.current();
            try (var registration = execution.onCancel(cancelled::countDown)) {
                entered.countDown();
                if (!cancelled.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test model never cancelled");
                execution.check();
                return GOOD;
            } catch (RequestStoppedException stopped) { throw stopped; }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        };
    }
    @Test void stopEndpointIsOwnerScopedAndPreventsSqlAndSuccessHistory() throws Exception {
        try (var registry = new WorkflowRegistry()) {
            var entered = new CountDownLatch(1); var mvc = http(registry, blockingModel(entered));
            var worker = Executors.newSingleThreadExecutor(); UUID id = UUID.randomUUID();
            try {
                var future = worker.submit(() -> mvc.perform(post("/api/chat/question").header("X-Request-Id", id)
                        .contentType(MediaType.APPLICATION_JSON).content(QUESTION)).andReturn());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                mvc.perform(post("/api/chat/requests/" + id + "/cancel").principal(() -> "other-user"))
                        .andExpect(status().isNotFound());
                assertEquals("RUNNING", registry.find(id, "test-reader").status());
                mvc.perform(post("/api/chat/requests/" + id + "/cancel")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CANCELLED"));
                var response = future.get(3, TimeUnit.SECONDS).getResponse();
                assertEquals(409, response.getStatus());
                assertEquals("REQUEST_CANCELLED", mapper.readTree(response.getContentAsString()).path("errorCode").asText());
                assertEquals(id.toString(), mapper.readTree(response.getContentAsString()).path("requestId").asText());
                assertEquals(0, queries.get());
                assertTrue(conversations.list("test-reader", new SchemaFingerprint(mapper).of(schemas), 0).items().isEmpty());
            } finally { worker.shutdownNow(); }
        }
    }
    @Test void totalBudgetTriggersSameCleanupWithoutClickingStop() throws Exception {
        try (var registry = new WorkflowRegistry(null, Duration.ofMillis(100))) {
            UUID id = UUID.randomUUID(); var mvc = http(registry, blockingModel(new CountDownLatch(1)));
            mvc.perform(post("/api/chat/question").header("X-Request-Id", id)
                    .contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                    .andExpect(status().isRequestTimeout()).andExpect(jsonPath("$.errorCode").value("REQUEST_TIMEOUT"));
            assertEquals("TIMED_OUT", registry.find(id, "test-reader").status());
            assertEquals(0, queries.get());
        }
    }
    @Test void invalidPayloadAndMissingStatusAlsoKeepResponseRequestId() throws Exception {
        try (var registry = new WorkflowRegistry()) {
            var mvc = http(registry, messages -> GOOD); UUID id = UUID.randomUUID();
            mvc.perform(post("/api/chat/question").header("X-Request-Id", id)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(header().string("X-Request-Id", id.toString()))
                    .andExpect(jsonPath("$.requestId").value(id.toString()));
            mvc.perform(get("/api/chat/requests/" + UUID.randomUUID())).andExpect(status().isNotFound());
        }
    }
}
