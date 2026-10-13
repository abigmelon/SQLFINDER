package com.sqlfinder.chat.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.RequestMode;
import com.sqlfinder.chat.application.ConversationRepository;
import com.sqlfinder.chat.application.HistoryStorageException;
import com.sqlfinder.chat.application.WorkflowRun;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** JDBC会话存储 */
public class JdbcConversationRepository implements ConversationRepository, AutoCloseable {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public JdbcConversationRepository(DataSource source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
        this.jdbc = new JdbcTemplate(source);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        safely(() -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS history_schema_version (version INTEGER PRIMARY KEY)");
            Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM history_schema_version", Integer.class);
            if (version > 3) throw new IllegalStateException("历史数据库版本高于当前应用版本");
            String[] migrations = {"V1__conversations.sql", "V2__result_snapshots.sql", "V3__workflow_runs.sql"};
            for (int next = version + 1; next <= migrations.length; next++) {
                try (var connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/history/" + migrations[next - 1]));
                } catch (SQLException ex) { throw new HistoryStorageException(ex); }
                jdbc.update("INSERT INTO history_schema_version(version) VALUES (?)", next);
            }
            return null;
        });
    }

    public boolean workflowExists(UUID id) {
        return safely(() -> jdbc.queryForObject("SELECT COUNT(*) FROM history_workflow_run WHERE request_id=?", Long.class, id) > 0);
    }

    public Optional<WorkflowRun> workflow(UUID id, String userId) {
        return safely(() -> jdbc.query("SELECT run_json FROM history_workflow_run WHERE request_id=? AND user_id=?",
                (rs, index) -> {
                    try { return mapper.readValue(rs.getString(1), WorkflowRun.class); }
                    catch (JsonProcessingException ex) { throw new HistoryStorageException(ex); }
                }, id, userId).stream().findFirst());
    }

    public void saveWorkflow(WorkflowRun run) {
        safely(() -> transactions.execute(status -> {
            String json;
            try { json = mapper.writeValueAsString(run); }
            catch (JsonProcessingException ex) { throw new HistoryStorageException(ex); }
            int updated = jdbc.update("UPDATE history_workflow_run SET run_json=? WHERE request_id=? AND user_id=?",
                    json, run.requestId(), run.userId());
            if (updated == 0) {
                jdbc.update("INSERT INTO history_workflow_run(request_id,user_id,run_json,started_at) VALUES (?,?,?,?)",
                        run.requestId(), run.userId(), json, run.startedAt());
            }
            return null;
        }));
    }

    @Override public Optional<Conversation> conversation(UUID id, String userId) {
        return safely(() -> jdbc.query("SELECT * FROM history_conversation WHERE id=? AND user_id=?",
                (rs, index) -> conversation(rs), id, userId).stream().findFirst());
    }

    @Override public Optional<Turn> turn(UUID id, UUID conversationId) {
        return safely(() -> jdbc.query("SELECT * FROM history_turn WHERE id=? AND conversation_id=?",
                (rs, index) -> turn(rs), id, conversationId).stream().findFirst());
    }

    @Override public List<Conversation> conversations(String userId, String fingerprint, int offset, int limit) {
        return safely(() -> jdbc.query("SELECT * FROM history_conversation WHERE user_id=? AND fingerprint=? "
                        + "ORDER BY last_activity_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, index) -> conversation(rs), userId, fingerprint, limit, offset));
    }

    @Override public Optional<Turn> ownedTurn(UUID id, String userId) {
        return safely(() -> jdbc.query("SELECT t.* FROM history_turn t JOIN history_conversation c "
                        + "ON c.id=t.conversation_id WHERE t.id=? AND c.user_id=?",
                (rs, index) -> turn(rs), id, userId).stream().findFirst());
    }

    @Override public List<Turn> turns(UUID conversationId, int offset, int limit) {
        return safely(() -> jdbc.query("SELECT * FROM history_turn WHERE conversation_id=? "
                        + "ORDER BY sequence_id DESC LIMIT ? OFFSET ?",
                (rs, index) -> turn(rs), conversationId, limit, offset));
    }

    @Override public void append(Conversation conversation, Turn turn) {
        safely(() -> transactions.execute(status -> {
            // 保存执行器已限制大小的返回快照；模型上下文仍只提取摘要。
            String json;
            try { json = mapper.writeValueAsString(turn.answer()); }
            catch (JsonProcessingException ex) { throw new HistoryStorageException(ex); }
            if (conversation(conversation.id(), conversation.userId()).isEmpty()) {
                jdbc.update("INSERT INTO history_conversation VALUES (?,?,?,?,?,?)", conversation.id(),
                        conversation.userId(), conversation.fingerprint(), conversation.title(),
                        conversation.createdAt().toEpochMilli(), conversation.lastActivityAt().toEpochMilli());
            }
            // Lock the conversation so concurrent follow-ups update activity monotonically.
            jdbc.queryForObject("SELECT id FROM history_conversation WHERE id=? FOR UPDATE", UUID.class, conversation.id());
            if (turn.parentTurnId() != null && turn(turn.parentTurnId(), conversation.id()).isEmpty()) {
                throw new IllegalStateException("前置轮次不属于当前会话");
            }
            jdbc.update("INSERT INTO history_turn(id,conversation_id,parent_turn_id,regenerated_from,request_mode,"
                            + "answer_json,row_count,created_at,result_saved) VALUES (?,?,?,?,?,?,?,?,?)",
                    turn.id(), conversation.id(), turn.parentTurnId(), turn.regeneratedFrom(), turn.mode().name(),
                    json, turn.rowCount(), turn.createdAt().toEpochMilli(), turn.resultSaved());
            jdbc.update("UPDATE history_conversation SET last_activity_at=GREATEST(last_activity_at,?) WHERE id=?",
                    turn.createdAt().toEpochMilli(), conversation.id());
            return null;
        }));
    }

    private Conversation conversation(ResultSet rs) throws SQLException {
        return new Conversation(rs.getObject("id", UUID.class), rs.getString("user_id"), rs.getString("fingerprint"),
                rs.getString("title"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("last_activity_at")));
    }

    private Turn turn(ResultSet rs) throws SQLException {
        try {
            return new Turn(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                    rs.getObject("parent_turn_id", UUID.class), rs.getObject("regenerated_from", UUID.class),
                    RequestMode.valueOf(rs.getString("request_mode")),
                    mapper.readValue(rs.getString("answer_json"), AnswerResponse.class), rs.getInt("row_count"),
                    Instant.ofEpochMilli(rs.getLong("created_at")), rs.getBoolean("result_saved"));
        } catch (JsonProcessingException ex) { throw new HistoryStorageException(ex); }
    }

    private <T> T safely(Supplier<T> action) {
        try { return action.get(); }
        catch (HistoryStorageException ex) { throw ex; }
        catch (RuntimeException ex) { throw new HistoryStorageException(ex); }
    }

    @Override public void close() throws Exception {
        if (source instanceof AutoCloseable closeable) closeable.close();
    }
}
