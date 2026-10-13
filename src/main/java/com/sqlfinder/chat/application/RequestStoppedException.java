package com.sqlfinder.chat.application;

import java.util.UUID;

/** 请求停止异常 */
public class RequestStoppedException extends RuntimeException {
    private final UUID requestId;
    private final String code;

    public RequestStoppedException(UUID requestId, String code) {
        super("REQUEST_TIMEOUT".equals(code) ? "本次问数超过总时限，请缩小问题范围后重试。" : "本次问数已停止。");
        this.requestId = requestId;
        this.code = code;
    }

    public UUID requestId() { return requestId; }
    public String code() { return code; }
}
