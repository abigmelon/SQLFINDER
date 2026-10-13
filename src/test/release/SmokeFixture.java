package com.sqlfinder.auth.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlfinder.chat.application.WorkflowRun;
import com.sqlfinder.chat.infrastructure.JdbcConversationRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** 发布验收数据 */
public class SmokeFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        LoginSetup.writeAccount(root.resolve("config/auth.properties"), "release-test",
                "release-test-only-password".toCharArray(), "release-test-only-password".toCharArray());
        HikariConfig config = new HikariConfig();
        java.nio.file.Files.createDirectories(root.resolve("data"));
        config.setJdbcUrl("jdbc:h2:file:" + root.resolve("data/history").toString().replace('\\', '/'));
        config.setUsername("sa"); config.setPassword(""); config.setMaximumPoolSize(1);
        try (var repository = new JdbcConversationRepository(new HikariDataSource(config), new ObjectMapper())) {
            repository.saveWorkflow(new WorkflowRun(UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    "release-test", "QUERY_SUCCEEDED", "HISTORY", 1L, 5L, 4L,
                    Map.of("MODEL_REQUEST", 2L, "EXECUTION", 2L), null, null, null, null));
        }
    }
}
