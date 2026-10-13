package com.sqlfinder.chat.application;

import java.util.Map;
import java.util.UUID;

/** 问数运行记录 */
public record WorkflowRun(UUID requestId, String userId, String status, String stage,
                          long startedAt, Long finishedAt, long durationMs,
                          Map<String, Long> stageDurations, String errorCode, String failureStage,
                          UUID conversationId, UUID turnId) { }
