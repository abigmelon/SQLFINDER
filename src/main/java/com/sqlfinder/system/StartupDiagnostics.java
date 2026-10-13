package com.sqlfinder.system;

import com.sqlfinder.chat.application.ConversationRepository;
import com.sqlfinder.settings.DatabaseDiagnostics;
import com.sqlfinder.settings.ModelSettingsException;
import com.sqlfinder.settings.ModelSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** 启动状态诊断 */
@Service
public class StartupDiagnostics {
    private final Supplier<ModelSettingsService.View> models;
    private final Function<String, DatabaseDiagnostics.Result> database;
    private final ConversationRepository history;

    @Autowired
    public StartupDiagnostics(ModelSettingsService models, DatabaseDiagnostics database, ConversationRepository history) {
        this(models::view, database::check, history);
    }

    StartupDiagnostics(Supplier<ModelSettingsService.View> models, Function<String, DatabaseDiagnostics.Result> database, ConversationRepository history) {
        this.models = models;
        this.database = database;
        this.history = history;
    }

    public Report check(String username) {
        var model = models.get();
        Check modelCheck = new Check(model.apiKeyConfigured() ? "READY" : "NOT_CONFIGURED",
                model.apiKeyConfigured() ? "模型密钥已配置，实际可用性请在设置中测试。" : "请在设置中填写 DeepSeek API key。");
        DatabaseCheck databaseCheck;
        try {
            var result = database.apply(username);
            // Never disclose tables the current application account cannot access.
            var tables = result.tables().stream().filter(DatabaseDiagnostics.TableCheck::applicationAllowed)
                    .map(t -> new Table(t.table(), t.databaseReadable())).toList();
            boolean ready = "READ_ONLY".equals(result.privileges()) && !tables.isEmpty()
                    && tables.stream().allMatch(Table::readable);
            databaseCheck = new DatabaseCheck(ready ? "READY" : "NEEDS_ATTENTION", result.privileges(), tables,
                    ready ? "授权表可读取，当前数据库账号已确认只读。" : "请检查数据库只读权限与账号表授权。");
        } catch (ModelSettingsException ex) {
            databaseCheck = new DatabaseCheck("UNAVAILABLE", "UNKNOWN", List.of(), "数据库检查未通过，请在设置中检查连接与业务表。");
        } catch (RuntimeException ex) {
            databaseCheck = new DatabaseCheck("UNAVAILABLE", "UNKNOWN", List.of(), "数据库暂不可用，请检查本机配置。");
        }
        Check historyCheck;
        try {
            // Read one impossible-fingerprint page: checks the real repository without returning conversations.
            history.conversations(username, "startup-diagnostic", 0, 1);
            historyCheck = new Check("READY", "本地会话记录可读取。");
        } catch (RuntimeException ex) {
            historyCheck = new Check("UNAVAILABLE", "本地会话记录不可用，请检查数据目录权限。");
        }
        boolean ready = "READY".equals(modelCheck.status()) && "READY".equals(databaseCheck.status())
                && "READY".equals(historyCheck.status());
        return new Report(ready ? "READY" : "NEEDS_ATTENTION", modelCheck, databaseCheck, historyCheck);
    }

    public record Check(String status, String message) { }
    public record Table(String name, boolean readable) { }
    public record DatabaseCheck(String status, String privileges, List<Table> tables, String message) { }
    public record Report(String status, Check model, DatabaseCheck database, Check history) { }
}
