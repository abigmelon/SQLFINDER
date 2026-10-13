package com.sqlfinder.chat.api;

/** 会话请求异常 */
public class ConversationRequestException extends RuntimeException {
    private final String code;

    public ConversationRequestException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
