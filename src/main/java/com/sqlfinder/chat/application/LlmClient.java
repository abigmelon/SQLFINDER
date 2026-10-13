package com.sqlfinder.chat.application;

import com.sqlfinder.chat.model.PromptMessages;

/** 模型调用接口 */

public interface LlmClient {
    String generateSql(PromptMessages messages);
}
