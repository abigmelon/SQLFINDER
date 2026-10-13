package com.sqlfinder.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.infrastructure.JdbcConversationRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本地历史配置 */
@Configuration
public class HistoryConfiguration {
    @Bean(destroyMethod = "close")
    public JdbcConversationRepository conversationRepository(Environment environment, ObjectMapper mapper) throws Exception {
        Path path = Path.of(environment.getProperty("sqlfinder.history.path", "./data/history")).toAbsolutePath().normalize();
        if (path.toString().contains(";")) throw new IllegalArgumentException("历史数据库路径不能包含分号");
        Files.createDirectories(path.getParent());
        HikariConfig config = new HikariConfig();
        config.setPoolName("sqlfinder-history");
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:file:" + path.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        HikariDataSource source = new HikariDataSource(config);
        try { return new JdbcConversationRepository(source, mapper); }
        catch (Exception ex) { source.close(); throw ex; }
        // Keep this pool private: it must never replace the business DataSource bean.
    }
}
