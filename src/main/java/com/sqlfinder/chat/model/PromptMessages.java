package com.sqlfinder.chat.model;

/** 模型提示词消息 */

public record PromptMessages(
        String systemMessage,
        String userMessage
) {
}
