package com.sqlfinder.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询配置测试：默认值、配置绑定与非法边界
 */
class QueryExecutionPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(QueryExecutionProperties.class)
    static class TestConfiguration {
    }

    @Test
    void missingConfigurationUsesSafeDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            QueryExecutionProperties properties = context.getBean(QueryExecutionProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.timeoutSeconds()).isEqualTo(5);
            assertThat(properties.maxRows()).isEqualTo(1000);
        });
    }

    @Test
    void explicitConfigurationIsBound() {
        runner.withPropertyValues("sqlfinder.query.enabled=true",
                "sqlfinder.query.timeout-seconds=10", "sqlfinder.query.max-rows=20")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    QueryExecutionProperties properties = context.getBean(QueryExecutionProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.timeoutSeconds()).isEqualTo(10);
                    assertThat(properties.maxRows()).isEqualTo(20);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sqlfinder.query.timeout-seconds=0",
            "sqlfinder.query.timeout-seconds=31",
            "sqlfinder.query.max-rows=0",
            "sqlfinder.query.max-rows=1001"
    })
    void invalidLimitsPreventContextStartup(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("sqlfinder.query");
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,1", "30,1000"})
    void limitsAtBothSupportedBoundariesAreAccepted(String values) {
        String[] limits = values.split(",");
        runner.withPropertyValues("sqlfinder.query.timeout-seconds=" + limits[0],
                "sqlfinder.query.max-rows=" + limits[1]).run(context -> {
            assertThat(context).hasNotFailed();
            QueryExecutionProperties properties = context.getBean(QueryExecutionProperties.class);
            assertThat(properties.timeoutSeconds()).isEqualTo(Integer.parseInt(limits[0]));
            assertThat(properties.maxRows()).isEqualTo(Integer.parseInt(limits[1]));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlfinder.query.enabled=invalid",
            "sqlfinder.query.timeout-seconds=invalid", "sqlfinder.query.max-rows=invalid"})
    void invalidPropertyTypesPreventStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
