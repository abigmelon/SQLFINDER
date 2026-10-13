package com.sqlfinder.chat.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.api.*;
import com.sqlfinder.chat.application.*;
import com.sqlfinder.chat.application.ConversationRepository.Conversation;
import com.sqlfinder.chat.application.ConversationRepository.Turn;
import com.sqlfinder.chat.model.SqlValidationSummary;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 历史持久化测试 */
class JdbcConversationRepositoryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-12T00:00:00Z");

    private HikariDataSource source(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + directory.resolve(name).toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa"); config.setPassword(""); config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private AnswerResponse answer(String question) {
        return new AnswerResponse("QUERY_SUCCEEDED", question, "SELECT COUNT(*) AS n FROM tb_shop",
                List.of("n"), List.of(Map.of("n", "private-row-value")), "查询成功。",
                new SqlValidationSummary("v1", List.of("mybatis.tb_shop"), List.of("COUNT")), null, false);
    }

    @Test void fileReopensWithResultSnapshotsButFollowUpStillUsesOnlySummaries() throws Exception {
        UUID conversationId, turnId;
        try (var repository = new JdbcConversationRepository(source("restart"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var result = store.append(store.resolve("alice", new QuestionRequest("第一问", null), "scope"), answer("第一问"));
            conversationId = result.conversationId(); turnId = result.turnId();
            assertEquals("private-row-value", result.rows().get(0).get("n"));
        }
        try (var repository = new JdbcConversationRepository(source("restart"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
            var history = store.detail("alice", conversationId, "scope", 0);
            assertTrue(history.contextAvailable());
            assertEquals(1, history.turns().get(0).rowCount());
            assertTrue(history.turns().get(0).resultSaved());
            assertEquals("private-row-value", history.turns().get(0).answer().rows().get(0).get("n"));
            var followUp = store.resolve("alice", new QuestionRequest("继续", null, false, turnId, false), "scope");
            assertEquals("第一问", followUp.context().turns().get(0).question());
            assertEquals(conversationId, followUp.conversationId());
            assertFalse(mapper.writeValueAsString(followUp.context()).contains("private-row-value"));
        }
    }

    @Test void expirationStopsContextButRetainsHistoryAndSchemaMismatchDoesNotDelete() throws Exception {
        try (var repository = new JdbcConversationRepository(source("expiry"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var first = store.append(store.resolve("alice", new QuestionRequest("问题", null), "scope"), answer("问题"));
            var later = new ConversationStore(repository, Clock.fixed(now.plusSeconds(1800), ZoneOffset.UTC));
            assertFalse(later.detail("alice", first.conversationId(), "scope", 0).contextAvailable());
            assertEquals("CONTEXT_EXPIRED", assertThrows(ConversationRequestException.class, () -> later.resolve("alice",
                    new QuestionRequest("继续", null, false, first.turnId(), false), "scope")).code());
            assertEquals("CONTEXT_SCHEMA_CHANGED", assertThrows(ConversationRequestException.class,
                    () -> store.detail("alice", first.conversationId(), "different", 0)).code());
            assertEquals(1, store.list("alice", "scope", 0).items().size());
            assertTrue(store.list("alice", "different", 0).items().isEmpty());
        }
    }

    @Test void userIsolationCoversDetailListAndTurnLookup() throws Exception {
        try (var repository = new JdbcConversationRepository(source("isolation"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var first = store.append(store.resolve("alice", new QuestionRequest("私有问题", null), "scope"), answer("私有问题"));
            assertTrue(store.list("bob", "scope", 0).items().isEmpty());
            assertEquals("CONTEXT_NOT_FOUND", assertThrows(ConversationRequestException.class,
                    () -> store.detail("bob", first.conversationId(), "scope", 0)).code());
            assertEquals("CONTEXT_NOT_FOUND", assertThrows(ConversationRequestException.class,
                    () -> store.resolve("bob", new QuestionRequest("继续", null, false, first.turnId(), false), "scope")).code());
        }
    }

    @Test void appendRollsBackNewConversationOnBadParent() throws Exception {
        try (var repository = new JdbcConversationRepository(source("rollback"), mapper)) {
            UUID id = UUID.randomUUID();
            var conversation = new Conversation(id, "alice", "scope", "标题", now, now);
            var turn = new Turn(UUID.randomUUID(), id, UUID.randomUUID(), null, RequestMode.FOLLOW_UP, answer("问题"), 1, now);
            assertThrows(HistoryStorageException.class, () -> repository.append(conversation, turn));
            assertTrue(repository.conversation(id, "alice").isEmpty());
            assertTrue(repository.turns(id, 0, 10).isEmpty());
        }
    }

    @Test void regenerationAndBranchesSurviveReloadWithBoundedContext() throws Exception {
        try (var repository = new JdbcConversationRepository(source("branches"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var first = store.append(store.resolve("alice", new QuestionRequest("根问题", null), "scope"), answer("根问题"));
            var last = first;
            for (int i = 0; i < 8; i++) {
                String question = "后续" + i;
                last = store.append(store.resolve("alice", new QuestionRequest(question, null, false, last.turnId(), false), "scope"), answer(question));
            }
            var regenerate = store.resolve("alice", new QuestionRequest("篡改", null, false, first.turnId(), true), "scope");
            assertEquals("根问题", regenerate.question());
            assertTrue(regenerate.context().turns().isEmpty());
            var result = store.append(regenerate, answer("根问题"));
            assertEquals(first.turnId(), repository.turn(result.turnId(), first.conversationId()).orElseThrow().regeneratedFrom());
            assertEquals(5, store.resolve("alice", new QuestionRequest("继续", null, false, last.turnId(), false), "scope").context().turns().size());
            assertEquals(10, store.detail("alice", first.conversationId(), "scope", 0).turns().size());
        }
    }

    @Test void paginationUsesStableInsertionOrderAndRejectsInvalidOffsets() throws Exception {
        try (var repository = new JdbcConversationRepository(source("pages"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var first = store.append(store.resolve("alice", new QuestionRequest("根问题", null), "scope"), answer("根问题"));
            for (int i = 0; i < 51; i++) {
                store.append(store.resolve("alice", new QuestionRequest("分支" + i, null, false, first.turnId(), false), "scope"), answer("分支" + i));
                store.append(store.resolve("alice", new QuestionRequest("新会话" + i, null), "scope"), answer("新会话" + i));
            }
            var page = store.detail("alice", first.conversationId(), "scope", 0);
            assertTrue(page.hasMore()); assertEquals(50, page.turns().size());
            assertEquals("分支1", page.turns().get(0).answer().question());
            var older = store.detail("alice", first.conversationId(), "scope", page.nextOffset());
            assertFalse(older.hasMore()); assertEquals(2, older.turns().size());
            assertEquals("根问题", older.turns().get(0).answer().question());
            var list = store.list("alice", "scope", 0);
            assertTrue(list.hasMore()); assertEquals(2, store.list("alice", "scope", list.nextOffset()).items().size());
            assertThrows(ConversationRequestException.class, () -> store.list("alice", "scope", -1));
        }
    }

    @Test void concurrentFollowUpsAreNotLost() throws Exception {
        try (var repository = new JdbcConversationRepository(source("concurrent"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var first = store.append(store.resolve("alice", new QuestionRequest("根问题", null), "scope"), answer("根问题"));
            var threads = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 12; i++) tasks.add(threads.submit(() -> store.append(store.resolve("alice",
                        new QuestionRequest("并发追问", null, false, first.turnId(), false), "scope"), answer("并发追问"))));
                for (var task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(13, store.detail("alice", first.conversationId(), "scope", 0).turns().size());
            } finally { threads.shutdownNow(); }
        }
    }

    @Test void newerSchemaFailsClosedInsteadOfReinitializing() throws Exception {
        try (var repository = new JdbcConversationRepository(source("version"), mapper)) { }
        try (var source = source("version")) {
            new JdbcTemplate(source).update("INSERT INTO history_schema_version VALUES (4)");
            assertThrows(HistoryStorageException.class, () -> new JdbcConversationRepository(source, mapper));
            assertEquals(4, new JdbcTemplate(source).queryForObject("SELECT MAX(version) FROM history_schema_version", Integer.class));
        }
    }

    @Test void versionOneHistoryUpgradesWithoutInventingMissingResultRows() throws Exception {
        UUID conversationId = UUID.randomUUID(), turnId = UUID.randomUUID();
        try (var source = source("migration")) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE history_schema_version (version INTEGER PRIMARY KEY)");
            try (var connection = source.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/history/V1__conversations.sql"));
            }
            jdbc.update("INSERT INTO history_schema_version VALUES (1)");
            jdbc.update("INSERT INTO history_conversation VALUES (?,?,?,?,?,?)", conversationId,
                    "alice", "scope", "旧问题", now.toEpochMilli(), now.toEpochMilli());
            var summary = new AnswerResponse("QUERY_SUCCEEDED", "旧问题", "SELECT n FROM tb_shop",
                    List.of("n"), List.of(), "查询成功。").withConversation(conversationId, turnId, null, RequestMode.NEW);
            jdbc.update("INSERT INTO history_turn(id,conversation_id,request_mode,answer_json,row_count,created_at) VALUES (?,?,?,?,?,?)",
                    turnId, conversationId, "NEW", mapper.writeValueAsString(summary), 14, now.toEpochMilli());
        }
        try (var repository = new JdbcConversationRepository(source("migration"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var old = store.detail("alice", conversationId, "scope", 0).turns().get(0);
            assertFalse(old.resultSaved());
            assertEquals(14, old.rowCount());
            assertTrue(old.answer().rows().isEmpty());
            store.append(store.resolve("alice", new QuestionRequest("新问题", null, false, turnId, false), "scope"), answer("新问题"));
        }
        try (var repository = new JdbcConversationRepository(source("migration"), mapper)) {
            var turns = repository.turns(conversationId, 0, 50);
            assertEquals(2, turns.size());
            assertTrue(turns.get(0).resultSaved());
            assertEquals("private-row-value", turns.get(0).answer().rows().get(0).get("n"));
            assertFalse(turns.get(1).resultSaved());
        }
    }

    @Test void snapshotsPreserveNullExactNumbersTruncationAndEmptyResults() throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("amount", "9007199254740993.01"); row.put("returned", null);
        var response = new AnswerResponse("QUERY_SUCCEEDED", "金额与空值", "SELECT amount, returned FROM tb_shop",
                List.of("amount", "returned"), List.of(row), "查询成功。", null, null, true,
                List.of(new com.sqlfinder.chat.model.ResultColumn("amount", "DECIMAL", "DECIMAL", false, 30, 2, "exact-string")));
        UUID id;
        try (var repository = new JdbcConversationRepository(source("values"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            id = store.append(store.resolve("alice", new QuestionRequest("金额与空值", null), "scope"), response).conversationId();
        }
        try (var repository = new JdbcConversationRepository(source("values"), mapper)) {
            var store = new ConversationStore(repository, Clock.fixed(now, ZoneOffset.UTC));
            var turn = store.detail("alice", id, "scope", 0).turns().get(0);
            assertEquals(response.rows(), turn.answer().rows());
            assertEquals(response.columnMetadata(), turn.answer().columnMetadata());
            assertTrue(turn.answer().truncated());
            var empty = new AnswerResponse("QUERY_SUCCEEDED", "无匹配数据", "SELECT n FROM tb_shop", List.of("n"), List.of(), "查询成功。");
            var saved = store.append(store.resolve("alice", new QuestionRequest("无匹配数据", null), "scope"), empty);
            var emptyTurn = repository.turn(saved.turnId(), saved.conversationId()).orElseThrow();
            assertTrue(emptyTurn.resultSaved());
            assertEquals(0, emptyTurn.rowCount());
            assertTrue(emptyTurn.answer().rows().isEmpty());
        }
    }
}
