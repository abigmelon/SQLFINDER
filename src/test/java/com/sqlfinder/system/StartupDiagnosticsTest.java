package com.sqlfinder.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.application.ConversationRepository;
import com.sqlfinder.settings.DatabaseDiagnostics;
import com.sqlfinder.settings.ModelSettingsException;
import com.sqlfinder.settings.ModelSettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 启动诊断测试 */
class StartupDiagnosticsTest {
    static class History implements ConversationRepository {
        String checkedUser;
        boolean fail;
        @Override public List<Conversation> conversations(String user, String fingerprint, int offset, int limit) {
            checkedUser = user;
            assertEquals("startup-diagnostic", fingerprint); assertEquals(0, offset); assertEquals(1, limit);
            if (fail) throw new IllegalStateException("private-path");
            return List.of();
        }
        @Override public Optional<Conversation> conversation(UUID id, String user) { throw new UnsupportedOperationException(); }
        @Override public Optional<Turn> turn(UUID id, UUID conversation) { throw new UnsupportedOperationException(); }
        @Override public Optional<Turn> ownedTurn(UUID id, String user) { throw new UnsupportedOperationException(); }
        @Override public List<Turn> turns(UUID id, int offset, int limit) { throw new UnsupportedOperationException(); }
        @Override public void append(Conversation conversation, Turn turn) { throw new UnsupportedOperationException(); }
    }
    private final History history = new History();
    private boolean key = true;
    private Function<String, DatabaseDiagnostics.Result> check = user -> new DatabaseDiagnostics.Result("secret-account", "private-catalog", "READ_ONLY",
            List.of(new DatabaseDiagnostics.TableCheck("film", true, true), new DatabaseDiagnostics.TableCheck("private-table", true, false)), 1);
    private StartupDiagnostics service() {
        return new StartupDiagnostics(() -> new ModelSettingsService.View("DeepSeek", "https://api.deepseek.com", "deepseek-chat", key, 30, "saved"),
                user -> check.apply(user), history);
    }
    @Test void probesActualRepositoryAndOnlyReturnsAuthorizedTableNames() throws Exception {
        var result = service().check("reader");
        assertEquals("READY", result.status());
        assertEquals(List.of(new StartupDiagnostics.Table("film", true)), result.database().tables());
        assertEquals("reader", history.checkedUser);
        String json = new ObjectMapper().writeValueAsString(result);
        for (String secret : List.of("secret-account", "private-catalog", "private-table", "apiKey", "password")) assertFalse(json.contains(secret));
    }
    @Test void extraPrivilegesCannotBeReportedAsReady() {
        check = user -> new DatabaseDiagnostics.Result("root", "db", "EXTRA_PRIVILEGES", List.of(new DatabaseDiagnostics.TableCheck("film", true, true)), 1);
        assertEquals("NEEDS_ATTENTION", service().check("reader").status());
    }
    @Test void missingModelKeyIsNotReady() {
        key = false;
        assertEquals("NOT_CONFIGURED", service().check("reader").model().status());
    }
    @Test void failuresAreIndependentAndExceptionContentsNeverEscape() throws Exception {
        check = user -> { throw new ModelSettingsException(503, "CONNECTION_FAILED", "private-password"); };
        history.fail = true;
        var result = service().check("reader");
        assertEquals("READY", result.model().status());
        assertEquals("UNAVAILABLE", result.database().status());
        assertEquals("UNAVAILABLE", result.history().status());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("private-"));
    }
    @Test void noAuthorizedTablesOrUnreadableTablesRequireAttention() {
        check = user -> new DatabaseDiagnostics.Result("a", "d", "READ_ONLY", List.of(), 1);
        assertEquals("NEEDS_ATTENTION", service().check("reader").database().status());
        check = user -> new DatabaseDiagnostics.Result("a", "d", "READ_ONLY", List.of(new DatabaseDiagnostics.TableCheck("film", false, true)), 1);
        assertEquals("NEEDS_ATTENTION", service().check("reader").database().status());
    }
}
