package com.sqlfinder.chat.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** 问数请求 */
public record QuestionRequest(
        @NotBlank(message = "question 不能为空") String question,
        Long chatId,
        @JsonProperty("new_chat") @JsonAlias("newChat") Boolean newChat,
        UUID resource,
        boolean regenerated) {

    /** 兼容旧代码：未携带上下文时视为新对话。 */
    public QuestionRequest(String question, Long chatId) {
        this(question, chatId, null, null, false);
    }

    public RequestMode mode() {
        if (Boolean.TRUE.equals(newChat)) {
            if (resource != null || regenerated) {
                throw new ConversationRequestException("新对话不能携带前置轮次或重新生成标记。", "CONTEXT_REQUEST_INVALID");
            }
            return RequestMode.NEW;
        }
        if (regenerated) {
            if (resource == null) {
                throw new ConversationRequestException("重新生成必须提供前置轮次。", "CONTEXT_REQUEST_INVALID");
            }
            return RequestMode.REGENERATE;
        }
        if (resource != null) {
            return RequestMode.FOLLOW_UP;
        }
        if (Boolean.FALSE.equals(newChat)) {
            throw new ConversationRequestException("继续追问必须提供前置轮次。", "CONTEXT_REQUEST_INVALID");
        }
        return RequestMode.NEW;
    }
}
