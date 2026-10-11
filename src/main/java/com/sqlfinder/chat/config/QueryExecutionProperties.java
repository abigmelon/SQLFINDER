package com.sqlfinder.chat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 查询执行配置
 */
@Validated
@ConfigurationProperties(prefix = "sqlfinder.query")
public record QueryExecutionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") @Min(1) @Max(30) int timeoutSeconds,
        @DefaultValue("1000") @Min(1) @Max(1000) int maxRows) {
}