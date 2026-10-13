package com.sqlfinder.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型调用配置 */

@ConfigurationProperties(prefix = "sqlfinder.llm")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";
    private String apiKey;
    private int timeoutSeconds = 60;

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
