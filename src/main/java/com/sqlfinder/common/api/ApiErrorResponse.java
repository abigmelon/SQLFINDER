package com.sqlfinder.common.api;

/**
 * http错误响应
 * @param errorCode 错误码
 * @param message 提示
 * @param requestId 请求标识
 */
public record ApiErrorResponse(
        String errorCode,
        String message,
        String requestId
) {
}
