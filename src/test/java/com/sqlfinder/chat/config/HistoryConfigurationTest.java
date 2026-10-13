package com.sqlfinder.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.infrastructure.JdbcConversationRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 历史连接隔离测试 */
class HistoryConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner context(Path path) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(HistoryConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("sqlfinder.history.path=" + path,
                        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/business",
                        "spring.datasource.username=read-only-test", "spring.datasource.hikari.read-only=true");
    }

    @Test void privateHistoryPoolNeverReplacesTheReadOnlyBusinessDataSource() {
        Path path = directory.resolve("nested/history");
        context(path).run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(JdbcConversationRepository.class);
            var business = (HikariDataSource) app.getBean(DataSource.class);
            assertThat(business.getJdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:1/business");
            assertThat(business.isReadOnly()).isTrue();
            assertThat(Files.exists(directory.resolve("nested/history.mv.db"))).isTrue();
        });
    }

    @Test void corruptedHistoryDoesNotSilentlyFallBackToMemory() throws Exception {
        Files.writeString(directory.resolve("broken.mv.db"), "invalid-history-file");
        context(directory.resolve("broken")).run(app -> assertThat(app).hasFailed());
        assertThat(Files.readString(directory.resolve("broken.mv.db"))).isEqualTo("invalid-history-file");
    }

    @Test void pathCannotInjectH2ConnectionOptions() {
        context(directory.resolve("history;INIT=invalid")).run(app -> assertThat(app).hasFailed());
    }
}
