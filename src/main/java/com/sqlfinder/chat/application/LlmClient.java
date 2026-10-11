package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.PromptMessages;

public interface LlmClient {
    String generateSql(PromptMessages messages);
}
