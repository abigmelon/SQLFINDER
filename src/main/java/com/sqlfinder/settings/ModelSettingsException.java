package com.sqlfinder.settings;

/** 模型设置异常 */
public class ModelSettingsException extends RuntimeException {
    private final int status;
    private final String code;
    public ModelSettingsException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
