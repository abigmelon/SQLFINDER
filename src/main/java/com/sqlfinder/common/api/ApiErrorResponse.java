package com.sqlfinder.common.api;

/** HTTP错误响应 */
public record ApiErrorResponse(
        String errorCode,
        String message,
        String requestId
) {
}
