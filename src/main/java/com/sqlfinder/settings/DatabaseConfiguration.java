package com.sqlfinder.settings;

import java.util.List;

/** 数据库配置快照 */
public record DatabaseConfiguration(String host, int port, String catalog, String username,
                                    String password, boolean requireTls, List<String> tables) {
    public DatabaseConfiguration {
        if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*")
                || port < 1 || port > 65535 || !DatabaseDiagnostics.identifier(catalog)
                || username == null || !username.matches("[A-Za-z_][A-Za-z0-9_.-]{0,31}")
                || password == null || password.length() > 512 || password.chars().anyMatch(Character::isISOControl)
                || tables == null || tables.size() > 50 || tables.stream().anyMatch(t -> !DatabaseDiagnostics.identifier(t))
                || tables.stream().distinct().count() != tables.size()) {
            throw invalid();
        }
        tables = List.copyOf(tables);
    }
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + catalog
                + "?sslMode=" + (requireTls ? "VERIFY_IDENTITY" : "DISABLED")
                + "&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=5000"
                + "&allowMultiQueries=false&allowLoadLocalInfile=false&autoDeserialize=false";
    }
    public View view() { return new View(host,port,catalog,username,!password.isEmpty(),requireTls,tables); }
    @Override public String toString() { return "DatabaseConfiguration[password=[REDACTED]]"; }
    static ModelSettingsException invalid() {
        return new ModelSettingsException(400,"INVALID_DATABASE_SETTINGS",
                "请检查 MySQL 主机、端口、库名、账号和业务表；最多选择 50 张表，名称仅支持字母、数字和下划线。");
    }
    public record View(String host, int port, String catalog, String username, boolean passwordConfigured,
                       boolean requireTls, List<String> tables) { }
}
