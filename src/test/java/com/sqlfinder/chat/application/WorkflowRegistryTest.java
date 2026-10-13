package com.sqlfinder.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.api.ConversationRequestException;
import com.sqlfinder.chat.infrastructure.JdbcConversationRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 运行记录测试 */
class WorkflowRegistryTest {
    @TempDir Path directory;
    private JdbcConversationRepository repository() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + directory.resolve("history").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa"); config.setPassword(""); config.setMaximumPoolSize(2);
        return new JdbcConversationRepository(new HikariDataSource(config), new ObjectMapper());
    }
    @Test void runningAndFinishedRecordsAreOwnerScopedAndRequestIdsCannotBeReused() {
        try (var registry = new WorkflowRegistry()) {
            UUID id = UUID.randomUUID();
            var request = registry.begin(id, "alice");
            assertEquals("RUNNING", registry.find(id, "alice").status());
            assertEquals("REQUEST_NOT_FOUND", assertThrows(ConversationRequestException.class,
                    () -> registry.find(id, "bob")).code());
            assertThrows(ConversationRequestException.class, () -> registry.cancel(id, "bob"));
            assertEquals("RUNNING", registry.find(id, "alice").status());
            assertEquals("REQUEST_ID_REUSED", assertThrows(ConversationRequestException.class,
                    () -> registry.begin(id, "bob")).code());
            request.result("NEEDS_CLARIFICATION", null, null, null);
            registry.finish(request);
            assertEquals("NEEDS_CLARIFICATION", registry.cancel(id, "alice").status());
            assertThrows(ConversationRequestException.class, () -> registry.begin(id, "alice"));
        }
    }
    @Test void scheduledDeadlineCancelsAResourceWithoutWaitingForNextStage() throws Exception {
        try (var registry = new WorkflowRegistry(null, Duration.ofMillis(80))) {
            var request = registry.begin(UUID.randomUUID(), "alice");
            var cancelled = new CountDownLatch(1);
            request.onCancel(cancelled::countDown);
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertEquals("TIMED_OUT", registry.find(request.requestId(), "alice").status());
            assertEquals("REQUEST_TIMEOUT", assertThrows(RequestStoppedException.class, request::check).code());
            registry.finish(request);
        }
    }
    @Test void auditSurvivesRestartAndKeepsFailureStageSeparateFromHistorySaving() throws Exception {
        UUID id = UUID.randomUUID(); UUID conversation = UUID.randomUUID();
        try (var repository = repository(); var registry = new WorkflowRegistry(repository, Duration.ofSeconds(2))) {
            var request = registry.begin(id, "alice");
            request.conversation(conversation);
            request.stage("VALIDATION"); request.failedAtCurrentStage();
            request.stage("HISTORY"); request.result("SQL_REJECTED", "SQL_UNSAFE_FUNCTION", conversation, null);
            registry.finish(request);
        }
        try (var repository = repository(); var registry = new WorkflowRegistry(repository, Duration.ofSeconds(2))) {
            var stored = registry.find(id, "alice");
            assertEquals("SQL_REJECTED", stored.status());
            assertEquals("VALIDATION", stored.failureStage());
            assertEquals("HISTORY", stored.stage());
            assertEquals("SQL_UNSAFE_FUNCTION", stored.errorCode());
            assertEquals(conversation, stored.conversationId());
            assertTrue(stored.stageDurations().containsKey("VALIDATION"));
            assertThrows(ConversationRequestException.class, () -> registry.find(id, "bob"));
            assertThrows(ConversationRequestException.class, () -> registry.begin(id, "bob"));
        }
    }
}
