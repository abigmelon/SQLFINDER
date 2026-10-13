package com.sqlfinder.settings;

/** 模型设置请求 */
public record ModelSettingsRequest(String model, String apiKey, Integer timeoutSeconds, boolean clearApiKey) {
    @Override public String toString() { return "ModelSettingsRequest[REDACTED]"; }
}
