package com.sqlfinder.settings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 数据库启动配置 */
public class DatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private final Path file;
    public DatabaseEnvironmentPostProcessor() { this(DatabaseConfigFile.DEFAULT_PATH); }
    DatabaseEnvironmentPostProcessor(Path file) { this.file = file; }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!Files.exists(file)) return;
        try {
            var c = DatabaseConfigFile.read(file);
            Map<String,Object> p = new LinkedHashMap<>();
            p.put("spring.datasource.url",c.jdbcUrl()); p.put("spring.datasource.username",c.username());
            p.put("spring.datasource.password",c.password()); p.put("spring.datasource.driver-class-name","com.mysql.cj.jdbc.Driver");
            // Hikari URL/credentials aliases must not leave an old target in the pool.
            p.put("spring.datasource.hikari.jdbc-url",c.jdbcUrl()); p.put("spring.datasource.hikari.username",c.username());
            p.put("spring.datasource.hikari.password",c.password()); p.put("spring.datasource.hikari.read-only",true);
            p.put("spring.datasource.hikari.catalog",c.catalog());
            p.put("spring.datasource.hikari.connection-timeout",6000);
            p.put("sqlfinder.metadata.catalog",c.catalog());
            p.put("sqlfinder.metadata.allowed-tables",c.tables());
            for (int i=0;i<c.tables().size();i++) p.put("sqlfinder.metadata.allowed-tables["+i+"]",c.tables().get(i));
            env.getPropertySources().addFirst(new MapPropertySource("sqlfinderSavedDatabase",p));
        } catch (Exception ex) {
            // Do not attach the cause: malformed configuration may contain a password.
            throw new IllegalStateException("已保存的数据库配置无法读取，请检查 config/database.properties；未回退到其他连接。");
        }
    }
}
