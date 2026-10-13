package com.sqlfinder.chat.model;

import java.util.List;
import java.util.UUID;

/** 会话提示词摘要 */
public record ConversationPromptContext(
        UUID conversationId,
        List<ConversationTurnContext> turns) {
    public ConversationPromptContext {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public static ConversationPromptContext empty() {
        return new ConversationPromptContext(null, List.of());
    }
}
