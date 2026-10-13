package com.sqlfinder.chat.application;

import com.sqlfinder.chat.api.ConversationRequestException;
import com.sqlfinder.chat.infrastructure.JdbcConversationRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 运行状态管理 */
@Component
public class WorkflowRegistry implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);
    private final Map<UUID, RequestExecution> active = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> deadlines = new ConcurrentHashMap<>();
    private final Map<UUID, WorkflowRun> recent = new LinkedHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sqlfinder-request-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final JdbcConversationRepository repository;
    private final Duration budget;

    public WorkflowRegistry() { this(null, Duration.ofSeconds(120)); }
    public WorkflowRegistry(JdbcConversationRepository repository, Duration budget) {
        this.repository = repository;
        this.budget = budget;
    }
    @Autowired
    public WorkflowRegistry(ObjectProvider<JdbcConversationRepository> repositories,
                            @Value("${sqlfinder.workflow.timeout-seconds:120}") int seconds) {
        this(repositories.getIfAvailable(), Duration.ofSeconds(seconds));
        if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("问数总时限必须在 1 到 600 秒之间");
    }

    public RequestExecution begin(UUID id, String userId) {
        synchronized (recent) {
            if (recent.containsKey(id) || (repository != null && repository.workflowExists(id))) throw reused();
        }
        RequestExecution execution = new RequestExecution(id, userId, budget);
        if (active.putIfAbsent(id, execution) != null) throw reused();
        deadlines.put(id, timer.schedule(() -> {
            execution.expire();
            save(execution.snapshot());
        }, budget.toMillis(), TimeUnit.MILLISECONDS));
        return execution;
    }
    public long timeoutSeconds() { return budget.toSeconds(); }
    public WorkflowRun find(UUID id, String userId) {
        RequestExecution execution = active.get(id);
        if (execution != null && execution.userId().equals(userId)) return execution.snapshot();
        synchronized (recent) {
            WorkflowRun run = recent.get(id);
            if (run != null && run.userId().equals(userId)) return run;
        }
        if (repository != null) return repository.workflow(id, userId).orElseThrow(WorkflowRegistry::notFound);
        throw notFound();
    }
    public WorkflowRun cancel(UUID id, String userId) {
        RequestExecution execution = active.get(id);
        if (execution == null) return find(id, userId);
        if (!execution.userId().equals(userId)) throw notFound();
        execution.cancel();
        WorkflowRun snapshot = execution.snapshot();
        save(snapshot);
        return snapshot;
    }
    public void finish(RequestExecution execution) {
        ScheduledFuture<?> deadline = deadlines.remove(execution.requestId());
        if (deadline != null) deadline.cancel(false);
        WorkflowRun run = execution.snapshot();
        save(run);
        synchronized (recent) {
            recent.put(run.requestId(), run);
            while (recent.size() > 200) recent.remove(recent.keySet().iterator().next());
        }
        active.remove(execution.requestId(), execution);
    }
    private void save(WorkflowRun run) {
        if (repository == null) return;
        try { repository.saveWorkflow(run); }
        catch (RuntimeException ex) {
            // 审计故障不能覆盖原始问数结果或取消原因；日志不包含 SQL、Prompt 或凭据。
            log.warn("requestId={} auditStorageUnavailable exceptionType={}", run.requestId(), ex.getClass().getSimpleName());
        }
    }
    private static ConversationRequestException notFound() {
        return new ConversationRequestException("未找到本次问数记录。", "REQUEST_NOT_FOUND");
    }
    private static ConversationRequestException reused() {
        return new ConversationRequestException("请求标识已使用，请重新发送问题。", "REQUEST_ID_REUSED");
    }
    @PreDestroy @Override public void close() {
        active.values().forEach(RequestExecution::cancel);
        timer.shutdownNow();
    }
}
