package com.sqlfinder.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.auth.support.TestTableAuthorization;
import com.sqlfinder.chat.api.ConversationRequestException;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.api.RequestMode;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.PromptMessages;
import com.sqlfinder.datasource.application.MetadataService;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.datasource.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 会话上下文测试 */

class ConversationContextTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<List<TableSchema>> schema = new AtomicReference<>(
            List.of(new TableSchema("mybatis", "tb_shop", "", List.of())));

    private QuestionOrchestrator orchestrator(AtomicReference<PromptMessages> lastPrompt) {
        MetadataService metadata = schema::get;
        UserMetadataService authorization = TestTableAuthorization.forMetadata(metadata);
        return new QuestionOrchestrator(authorization, new PromptBuilder(mapper),
                messages -> {
                    lastPrompt.set(messages);
                    return "{\"success\":true,\"sql\":\"SELECT COUNT(*) FROM tb_shop\",\"message\":null}";
                }, new ModelOutputParser(mapper), new SqlValidator(),
                (userId, candidate) -> { throw new AssertionError("测试关闭了查询执行"); },
                new QueryExecutionProperties(false, 5, 1000),
                com.sqlfinder.chat.support.ConversationTestStores.create(), new SchemaFingerprint(mapper));
    }

    @Test
    void newChatCreatesTurnAndFollowUpUsesBoundedStructuredContext() {
        AtomicReference<PromptMessages> prompt = new AtomicReference<>();
        QuestionOrchestrator app = orchestrator(prompt);

        var first = app.ask("local-user", new QuestionRequest("统计商铺数", null, true, null, false));
        assertThat(first.mode()).isEqualTo(RequestMode.NEW);
        assertThat(first.conversationId()).isNotNull();
        assertThat(first.turnId()).isNotNull();

        var followUp = app.ask("local-user", new QuestionRequest("按数量降序排列", null,
                false, first.turnId(), false));
        assertThat(followUp.mode()).isEqualTo(RequestMode.FOLLOW_UP);
        assertThat(followUp.conversationId()).isEqualTo(first.conversationId());
        assertThat(followUp.parentTurnId()).isEqualTo(first.turnId());
        assertThat(prompt.get().userMessage()).contains("统计商铺数");
        assertThat(prompt.get().userMessage()).contains("SELECT COUNT(*) FROM tb_shop");
        assertThat(prompt.get().userMessage()).contains("FOLLOW_UP");
    }

    @Test
    void regenerateUsesOriginalQuestionAndCreatesAnotherTurn() {
        AtomicReference<PromptMessages> prompt = new AtomicReference<>();
        QuestionOrchestrator app = orchestrator(prompt);
        var first = app.ask("local-user", new QuestionRequest("统计商铺数", null, true, null, false));

        var regenerated = app.ask("local-user", new QuestionRequest("客户端篡改的问题", null,
                false, first.turnId(), true));
        assertThat(regenerated.mode()).isEqualTo(RequestMode.REGENERATE);
        assertThat(regenerated.question()).isEqualTo("统计商铺数");
        assertThat(regenerated.turnId()).isNotEqualTo(first.turnId());
        assertThat(regenerated.parentTurnId()).isEqualTo(first.turnId());
        assertThat(prompt.get().userMessage()).contains("REGENERATE");
    }

    @Test
    void anotherUserCannotUseTheParentTurn() {
        QuestionOrchestrator app = orchestrator(new AtomicReference<>());
        var first = app.ask("local-user", new QuestionRequest("统计商铺数", null, true, null, false));

        var error = assertThrows(ConversationRequestException.class,
                () -> app.ask("test-reader", new QuestionRequest("继续", null, false, first.turnId(), false)));
        assertThat(error.code()).isEqualTo("CONTEXT_NOT_FOUND");
    }

    @Test
    void schemaChangeInvalidatesTheConversation() {
        QuestionOrchestrator app = orchestrator(new AtomicReference<>());
        var first = app.ask("local-user", new QuestionRequest("统计商铺数", null, true, null, false));
        schema.set(List.of(new TableSchema("mybatis", "tb_shop_type", "", List.of())));

        var error = assertThrows(ConversationRequestException.class,
                () -> app.ask("local-user", new QuestionRequest("继续", null, false, first.turnId(), false)));
        assertThat(error.code()).isEqualTo("CONTEXT_SCHEMA_CHANGED");
    }

    @Test
    void invalidContextCombinationsAreRejectedBeforeARequestIsBuilt() {
        assertThrows(ConversationRequestException.class,
                () -> new QuestionRequest("问题", null, true, java.util.UUID.randomUUID(), false).mode());
        assertThrows(ConversationRequestException.class,
                () -> new QuestionRequest("问题", null, false, null, false).mode());
        assertThrows(ConversationRequestException.class,
                () -> new QuestionRequest("问题", null, false, null, true).mode());
    }

    @Test
    void requestJsonUsesTheDocumentedSnakeCaseFields() throws Exception {
        var parsed = mapper.readValue("{\"question\":\"继续\",\"new_chat\":false,\"resource\":\""
                + "00000000-0000-0000-0000-000000000001\",\"regenerated\":true}", QuestionRequest.class);
        assertThat(parsed.mode()).isEqualTo(RequestMode.REGENERATE);
        assertThat(parsed.resource()).hasToString("00000000-0000-0000-0000-000000000001");
    }
}
