package com.sqlfinder.settings;

import com.sqlfinder.auth.config.AuthProperties;
import com.sqlfinder.datasource.config.MetadataProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** 数据库权限诊断 */
@Service
public class DatabaseDiagnostics {
    private final Supplier<DataSource> source;
    private final MetadataProperties metadata;
    private final AuthProperties accounts;
    @Autowired public DatabaseDiagnostics(ObjectProvider<DataSource> source, MetadataProperties metadata, AuthProperties accounts) {
        this(source::getIfAvailable, metadata, accounts);
    }
    DatabaseDiagnostics(Supplier<DataSource> source, MetadataProperties metadata, AuthProperties accounts) {
        this.source=source; this.metadata=metadata; this.accounts=accounts;
    }
    public View configuration(String username) {
        List<String> grants=accounts.users().stream().filter(a->a.enabled() && a.username().equalsIgnoreCase(username))
                .findFirst().map(AuthProperties.Account::allowedTables).orElse(List.of());
        return new View(metadata.getCatalog(), List.copyOf(metadata.getAllowedTables()), grants);
    }
    public Result check(String username) {
        View view=configuration(username);
        if (!identifier(view.catalog()) || view.tables().isEmpty() || view.tables().size()>50
                || view.tables().stream().anyMatch(t->!identifier(t))) throw new ModelSettingsException(400,
                "DATASOURCE_CONFIG_INVALID", "请检查数据库名和业务表白名单；诊断一次最多支持 50 张表。");
        DataSource ds=source.get();
        if (ds==null) throw unavailable();
        long started=System.nanoTime();
        try (Connection connection=ds.getConnection()) {
            String account, catalog;
            try (var statement=connection.createStatement()) {
                statement.setQueryTimeout(5); statement.setMaxRows(1);
                try (var rows=statement.executeQuery("SELECT CURRENT_USER(), DATABASE()")) {
                    if (!rows.next()) throw unavailable();
                    account=rows.getString(1); catalog=rows.getString(2);
                }
            }
            if (!view.catalog().equals(catalog)) throw new ModelSettingsException(400,"DATASOURCE_CATALOG_MISMATCH",
                    "当前连接的数据库与业务表配置不一致，请核对连接 URL 和 metadata.catalog。");
            List<TableCheck> tables=new ArrayList<>();
            for (String table:view.tables()) {
                if (System.nanoTime()-started>15_000_000_000L) throw new ModelSettingsException(503,
                        "DATASOURCE_CHECK_TIMEOUT","数据库检查耗时过长，请检查数据库状态后重试。");
                boolean readable;
                try (var statement=connection.createStatement()) {
                    statement.setQueryTimeout(5); statement.setMaxRows(1);
                    // LIMIT 0 验证列读取权限，不返回业务行，不修改连接状态。
                    try (var rows=statement.executeQuery("SELECT * FROM `"+catalog+"`.`"+table+"` LIMIT 0")) { readable=true; }
                } catch (SQLException ex) { readable=false; }
                tables.add(new TableCheck(table,readable,view.accountTables().contains(catalog+"."+table)));
            }
            String privileges="UNKNOWN";
            try (var statement=connection.createStatement()) {
                statement.setQueryTimeout(5); statement.setMaxRows(51);
                try (var rows=statement.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                    List<String> grants=new ArrayList<>();
                    while (rows.next() && grants.size()<51) grants.add(rows.getString(1));
                    if (grants.size()<51) privileges=classifyGrants(grants);
                }
            } catch (SQLException ignored) { /* 角色、代理及查询受限时不能声称已确认只读。 */ }
            return new Result(account,catalog,privileges,List.copyOf(tables),
                    (System.nanoTime()-started)/1_000_000);
        } catch (SQLException ex) { throw unavailable(); }
    }
    static String classifyGrants(List<String> grants) {
        if (grants.isEmpty()) return "UNKNOWN";
        boolean unknown=false;
        for (String grant:grants) {
            if (grant==null) { unknown=true; continue; }
            String upper=grant.toUpperCase(Locale.ROOT);
            if (upper.contains("WITH GRANT OPTION")) return "EXTRA_PRIVILEGES";
            int on=upper.indexOf(" ON ");
            if (!upper.startsWith("GRANT ") || on<0 || upper.substring(6,on).contains("(")) { unknown=true; continue; }
            for (String privilege:upper.substring(6,on).split(",")) {
                if (!List.of("USAGE","SELECT","SHOW VIEW").contains(privilege.trim())) return "EXTRA_PRIVILEGES";
            }
        }
        return unknown ? "UNKNOWN" : "READ_ONLY";
    }
    static boolean identifier(String value) { return value!=null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"); }
    private static ModelSettingsException unavailable() {
        return new ModelSettingsException(503,"DATASOURCE_CONNECTION_FAILED","无法连接当前业务数据库，请检查数据库服务和本机连接配置。");
    }
    public record View(String catalog,List<String> tables,List<String> accountTables) { }
    public record TableCheck(String table,boolean databaseReadable,boolean applicationAllowed) { }
    public record Result(String account,String catalog,String privileges,List<TableCheck> tables,long durationMs) { }
}
