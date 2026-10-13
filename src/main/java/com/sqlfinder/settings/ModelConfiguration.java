package com.sqlfinder.settings;

/** 模型配置快照 */
public record ModelConfiguration(String model, String apiKey, int timeoutSeconds) {
    @Override public String toString() {
        return "ModelConfiguration[model=" + model + ", apiKey=[REDACTED], timeoutSeconds=" + timeoutSeconds + "]";
    }
}
