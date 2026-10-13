package com.sqlfinder.chat.model;

import java.util.List;
import java.util.UUID;

/** 会话轮次摘要 */
public record ConversationTurnContext(
        UUID turnId,
        String question,
        String sql,
        List<String> validatedTables,
        List<String> resultColumns,
        int rowCount,
        boolean truncated,
        String status) {
    public ConversationTurnContext {
        validatedTables = validatedTables == null ? List.of() : List.copyOf(validatedTables);
        resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
    }
}
