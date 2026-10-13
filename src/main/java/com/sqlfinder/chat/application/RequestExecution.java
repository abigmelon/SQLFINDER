package com.sqlfinder.chat.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 单次请求控制 */
public final class RequestExecution {
    private static final ThreadLocal<RequestExecution> CURRENT = new ThreadLocal<>();
    private static final ExecutorService CANCELLATIONS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "sqlfinder-resource-cancel");
        thread.setDaemon(true);
        return thread;
    });
    private final UUID requestId;
    private final String userId;
    private final long startedAt = System.currentTimeMillis();
    private final long startedNanos = System.nanoTime();
    private final long budgetNanos;
    private final List<Runnable> callbacks = new ArrayList<>();
    private final Map<String, Long> stageDurations = new LinkedHashMap<>();
    private String status = "RUNNING";
    private String stage = "STARTING";
    private long stageStarted = startedNanos;
    private Long finishedAt;
    private long finishedNanos;
    private String errorCode;
    private String failureStage;
    private UUID conversationId;
    private UUID turnId;

    public RequestExecution(UUID requestId, String userId, Duration budget) {
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Request budget must be positive");
        this.requestId = java.util.Objects.requireNonNull(requestId);
        this.userId = java.util.Objects.requireNonNull(userId);
        this.budgetNanos = budget.toNanos();
    }

    public static RequestExecution current() { return CURRENT.get(); }
    public UUID requestId() { return requestId; }
    public String userId() { return userId; }
    public Scope bind() {
        RequestExecution previous = CURRENT.get();
        CURRENT.set(this);
        return new Scope(previous);
    }
    public static final class Scope implements AutoCloseable {
        private final RequestExecution previous;
        private boolean closed;
        private Scope(RequestExecution previous) { this.previous = previous; }
        @Override public void close() {
            if (!closed) {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                closed = true;
            }
        }
    }

    public long remainingMillis() {
        return Math.max(0, (budgetNanos - (System.nanoTime() - startedNanos)) / 1_000_000);
    }
    public void check() {
        if (System.nanoTime() - startedNanos >= budgetNanos) expire();
        synchronized (this) {
            if ("CANCELLED".equals(status) || "TIMED_OUT".equals(status)) {
                throw new RequestStoppedException(requestId, errorCode);
            }
        }
    }
    public AutoCloseable onCancel(Runnable action) {
        java.util.Objects.requireNonNull(action);
        boolean stopped;
        synchronized (this) {
            stopped = "CANCELLED".equals(status) || "TIMED_OUT".equals(status);
            if (!stopped && "RUNNING".equals(status)) callbacks.add(action);
        }
        if (stopped) dispatch(action);
        return () -> { synchronized (RequestExecution.this) { callbacks.remove(action); } };
    }
    public void cancel() { stop("CANCELLED", "REQUEST_CANCELLED"); }
    public void expire() { stop("TIMED_OUT", "REQUEST_TIMEOUT"); }
    private void stop(String state, String code) {
        List<Runnable> pending;
        synchronized (this) {
            if (!"RUNNING".equals(status)) return;
            finishTiming();
            status = state;
            errorCode = code;
            failureStage = stage;
            pending = List.copyOf(callbacks);
            callbacks.clear();
        }
        pending.forEach(RequestExecution::dispatch);
    }
    private static void dispatch(Runnable action) {
        CANCELLATIONS.execute(() -> {
            try { action.run(); }
            catch (RuntimeException ignored) { /* 原请求仍通过 check 获得停止原因。 */ }
        });
    }
    public void stage(String next) {
        check();
        synchronized (this) {
            if (!"RUNNING".equals(status)) { check(); return; }
            long now = System.nanoTime();
            stageDurations.merge(stage, (now - stageStarted) / 1_000_000, Long::sum);
            stage = next;
            stageStarted = now;
        }
    }
    public synchronized void result(String state, String code, UUID conversation, UUID turn) {
        check();
        if (!"RUNNING".equals(status)) return;
        finishTiming();
        status = state;
        errorCode = code;
        if (code != null && failureStage == null) failureStage = stage;
        if (conversation != null) conversationId = conversation;
        if (turn != null) turnId = turn;
        callbacks.clear();
    }
    public synchronized void conversation(UUID id) { conversationId = id; }
    public synchronized void failedAtCurrentStage() { failureStage = stage; }
    private void finishTiming() {
        finishedNanos = System.nanoTime();
        stageDurations.merge(stage, (finishedNanos - stageStarted) / 1_000_000, Long::sum);
        finishedAt = System.currentTimeMillis();
    }
    public synchronized WorkflowRun snapshot() {
        Map<String, Long> durations = new LinkedHashMap<>(stageDurations);
        long end = finishedAt == null ? System.nanoTime() : finishedNanos;
        if (finishedAt == null) durations.merge(stage, (end - stageStarted) / 1_000_000, Long::sum);
        return new WorkflowRun(requestId, userId, status, stage, startedAt, finishedAt,
                (end - startedNanos) / 1_000_000, java.util.Collections.unmodifiableMap(durations),
                errorCode, failureStage, conversationId, turnId);
    }
}
