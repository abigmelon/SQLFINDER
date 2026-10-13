package com.sqlfinder.chat.application;

/** SQL校验异常 */
public class SqlValidationException extends IllegalArgumentException {
    private final String code;

    public SqlValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SqlValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
