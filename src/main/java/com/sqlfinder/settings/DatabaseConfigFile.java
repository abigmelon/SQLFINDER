package com.sqlfinder.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Properties;

/** 数据库配置文件 */
public final class DatabaseConfigFile {
    public static final Path DEFAULT_PATH = Path.of("config", "database.properties");
    private DatabaseConfigFile() { }
    public static DatabaseConfiguration read(Path file) throws IOException {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        if (!"true".equals(p.getProperty("require-tls")) && !"false".equals(p.getProperty("require-tls"))) throw DatabaseConfiguration.invalid();
        var config = new DatabaseConfiguration(p.getProperty("host"), Integer.parseInt(p.getProperty("port")),
                p.getProperty("catalog"), p.getProperty("username"), p.getProperty("password"),
                Boolean.parseBoolean(p.getProperty("require-tls")), Arrays.asList(p.getProperty("tables").split(",", -1)));
        if (config.tables().isEmpty()) throw DatabaseConfiguration.invalid();
        return config;
    }
    public static void write(Path file, DatabaseConfiguration c) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), ".database-", ".tmp");
            Properties p = new Properties();
            p.setProperty("host", c.host()); p.setProperty("port", Integer.toString(c.port()));
            p.setProperty("catalog", c.catalog()); p.setProperty("username", c.username());
            p.setProperty("password", c.password()); p.setProperty("require-tls", Boolean.toString(c.requireTls()));
            p.setProperty("tables", String.join(",", c.tables()));
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(writer, "SQLFINDER private database settings; do not publish");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new ModelSettingsException(503,"DATABASE_SAVE_FAILED","连接未保存，请检查 config 目录的文件权限后重试。");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
}
