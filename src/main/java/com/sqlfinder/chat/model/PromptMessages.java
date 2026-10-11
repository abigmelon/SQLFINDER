package com.sqlfinder.chat.model;

public record PromptMessages(
        String systemMessage,
        String userMessage
) {
}
