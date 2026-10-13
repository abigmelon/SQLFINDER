package com.sqlfinder.common.api;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.api.ChatController;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.application.*;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.PromptMessages;
import com.sqlfinder.common.application.QuestionProcessingException;
import com.sqlfinder.common.application.QuestionProcessingException.Stage;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.auth.support.TestTableAuthorization;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 全局异常处理测试 */
class GlobalExceptionHandlerTest {
    private static final String PRIVATE_DETAIL = "private-password-model-token-detail";
    private static final String QUESTION = "{\"question\":\"统计商铺数\"}";
    private static final String MODEL_JSON =
            "{\"success\":true,\"sql\":\"SELECT COUNT(*) FROM tb_shop\",\"message\":null}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<TableSchema> schemas = List.of(new TableSchema("mybatis", "tb_shop", "", List.of()));

    private QuestionOrchestrator orchestrator(MetadataService metadata, PromptBuilder prompt, LlmClient model) {
        return new QuestionOrchestrator(TestTableAuthorization.forMetadata(metadata), prompt, model, new ModelOutputParser(mapper),
                new SqlValidator(), (userId, candidate) -> { throw new AssertionError("失败请求不能执行 SQL"); },
                new QueryExecutionProperties(false, 5, 1000),
                com.sqlfinder.chat.support.ConversationTestStores.create(), new SchemaFingerprint(mapper));
    }

    private MockMvc http(QuestionOrchestrator orchestrator) {
        return MockMvcBuilders.standaloneSetup(
                        new ChatController(orchestrator, new QueryExecutionProperties(false, 5, 1000)),
                        new UnexpectedController())
                .defaultRequest(get("/").principal(() -> "test-reader"))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockMvc normal() {
        return http(orchestrator(() -> schemas, new PromptBuilder(mapper), messages -> MODEL_JSON));
    }

    private MvcResult assertError(MvcResult response, int status, String code) throws Exception {
        assertEquals(status, response.getResponse().getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getResponse().getContentType())));
        var body = mapper.readTree(response.getResponse().getContentAsString());
        assertEquals(3, body.size(), "HTTP 异常只返回三个约定字段");
        assertEquals(code, body.path("errorCode").asText());
        assertFalse(body.path("message").asText().isBlank());
        assertDoesNotThrow(() -> UUID.fromString(body.path("requestId").asText()));
        assertFalse(response.getResponse().getContentAsString().contains(PRIVATE_DETAIL));
        return response;
    }

    @Test
    void metadataFailureStopsThePipelineAndReturns503() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        var original = new IllegalStateException(PRIVATE_DETAIL);
        var app = orchestrator(() -> { throw original; }, new PromptBuilder(mapper), messages -> {
            modelCalls.incrementAndGet(); return MODEL_JSON;
        });
        var wrapped = assertThrows(QuestionProcessingException.class,
                () -> app.ask("local-user", new QuestionRequest("统计", null)));
        assertEquals(Stage.METADATA, wrapped.getStage());
        assertSame(original, wrapped.getCause());
        assertError(http(app).perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION)).andReturn(), 503, "METADATA_UNAVAILABLE");
        assertEquals(0, modelCalls.get());
    }

    @Test
    void promptFailureStopsBeforeModelAndReturns500() throws Exception {
        PromptBuilder broken = new PromptBuilder(mapper) {
            @Override public PromptMessages build(String question, List<TableSchema> tables) {
                throw new IllegalStateException(PRIVATE_DETAIL);
            }
        };
        var app = orchestrator(() -> schemas, broken,
                messages -> { throw new AssertionError("提示词失败不能请求模型"); });
        assertError(http(app).perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION)).andReturn(), 500, "PROMPT_BUILD_FAILED");
    }

    @Test
    void modelRequestFailureReturns502WithoutCauseDetails() throws Exception {
        var app = orchestrator(() -> schemas, new PromptBuilder(mapper),
                messages -> { throw new IllegalStateException(PRIVATE_DETAIL); });
        assertError(http(app).perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION)).andReturn(), 502, "MODEL_REQUEST_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "[]", "{}",
            "{\"success\":true,\"sql\":null,\"message\":null}",
            "{\"success\":\"true\",\"sql\":\"SELECT id FROM tb_shop\",\"message\":null}",
            "{\"success\":false,\"sql\":null,\"message\":null}",
            "{\"success\":true,\"success\":false,\"sql\":null,\"message\":\"x\"}",
            "{\"success\":true,\"sql\":\"SELECT id FROM tb_shop\",\"message\":null} {}"})
    void invalidModelBodyReturns502(String modelBody) throws Exception {
        var app = orchestrator(() -> schemas, new PromptBuilder(mapper), messages -> modelBody);
        assertError(http(app).perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION)).andReturn(), 502, "MODEL_OUTPUT_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"question\":null}", "{\"question\":\"\"}", "{\"question\":\"   \"}"})
    void missingOrBlankQuestionReturns400BeforeModel(String body) throws Exception {
        var app = orchestrator(() -> { throw new AssertionError("无效参数不能读取元数据"); },
                new PromptBuilder(mapper), messages -> MODEL_JSON);
        assertError(http(app).perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn(), 400, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{\"question\":\"统计\",\"chatId\":\"not-a-number\"}"})
    void malformedOrMissingRequestBodyReturns400(String body) throws Exception {
        assertError(normal().perform(post("/api/chat/question").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn(), 400, "INVALID_JSON");
    }

    @Test
    void unsupportedMethodPreserves405AndAllowHeader() throws Exception {
        var response = normal().perform(get("/api/chat/question"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andReturn();
        assertError(response, 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void unsupportedRequestMediaPreserves415() throws Exception {
        assertError(normal().perform(post("/api/chat/question").contentType(MediaType.TEXT_PLAIN)
                .content(QUESTION)).andReturn(), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unacceptableResponseMediaStillReturnsStructured406() throws Exception {
        assertError(normal().perform(get("/api/chat/sql-policy").accept("image/png"))
                .andReturn(), 406, "NOT_ACCEPTABLE");
    }

    @Test
    void unexpectedExceptionReturnsSafe500AndUniqueRequestIds() throws Exception {
        var mvc = normal();
        var first = assertError(mvc.perform(get("/test/unexpected")).andReturn(), 500, "INTERNAL_ERROR");
        var second = assertError(mvc.perform(get("/test/unexpected")).andReturn(), 500, "INTERNAL_ERROR");
        assertNotEquals(mapper.readTree(first.getResponse().getContentAsString()).path("requestId").asText(),
                mapper.readTree(second.getResponse().getContentAsString()).path("requestId").asText());
    }

    @Test
    void unexpectedErrorStillReturnsJsonWithUnsupportedAccept() throws Exception {
        assertError(normal().perform(get("/test/unexpected").accept("image/png"))
                .andReturn(), 500, "INTERNAL_ERROR");
    }

    @Test
    void serverErrorLogMatchesResponseIdAndDoesNotLogPrivateCause() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var response = assertError(normal().perform(get("/test/unexpected")).andReturn(),
                    500, "INTERNAL_ERROR");
            String id = mapper.readTree(response.getResponse().getContentAsString()).path("requestId").asText();
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(id)));
            for (var event : logs.list) {
                assertFalse(event.getFormattedMessage().contains(PRIVATE_DETAIL));
                assertNull(event.getThrowableProxy(), "普通日志不记录可能包含凭据的底层堆栈");
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @RestController
    static class UnexpectedController {
        @GetMapping("/test/unexpected")
        public String unexpected() { throw new IllegalStateException(PRIVATE_DETAIL); }
    }
}
