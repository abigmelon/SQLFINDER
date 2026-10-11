package com.sqlfinder.chat.application;

/**
 * 查询执行异常
 */
public class QueryExecutionException extends RuntimeException {
    private final String code;

    public QueryExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public QueryExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
