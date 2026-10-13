package com.sqlfinder.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 数据库连接检测 */
@Service
public class MysqlDatabaseConnectionTester implements DatabaseConnectionTester {
    @FunctionalInterface interface Connector { Connection open(DatabaseConfiguration c) throws SQLException; }
    private final Connector connector;
    @Autowired public MysqlDatabaseConnectionTester() {
        this(c -> DriverManager.getConnection(c.jdbcUrl(),c.username(),c.password()));
    }
    MysqlDatabaseConnectionTester(Connector connector) { this.connector=connector; }
    @Override public Result inspect(DatabaseConfiguration c) {
        long start=System.nanoTime();
        try (Connection connection=connector.open(c)) {
            connection.setReadOnly(true);
            String account,catalog;
            try (var s=connection.createStatement()) {
                s.setQueryTimeout(5); s.setMaxRows(1);
                try (var r=s.executeQuery("SELECT CURRENT_USER(), DATABASE()")) {
                    if (!r.next()) throw failed();
                    account=r.getString(1); catalog=r.getString(2);
                }
            }
            if (!c.catalog().equals(catalog)) throw new ModelSettingsException(400,"DATASOURCE_CATALOG_MISMATCH","候选连接的数据库不匹配，请重新检查库名。");
            List<String> tables=new ArrayList<>(); boolean truncated=false;
            try (var s=connection.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME LIMIT 201")) {
                s.setString(1,c.catalog()); s.setQueryTimeout(5); s.setMaxRows(201);
                try (var r=s.executeQuery()) {
                    int count=0;
                    while (r.next()) {
                        if (++count>200) { truncated=true; break; }
                        String table=r.getString(1);
                        if (DatabaseDiagnostics.identifier(table)) tables.add(table);
                    }
                }
            }
            List<Table> previews=new ArrayList<>();
            for (String table:c.tables()) {
                if (!tables.contains(table)) throw new ModelSettingsException(400,"DATABASE_TABLE_UNAVAILABLE","选中的业务表不存在、不可见或超出发现范围，请重新测试并选表。");
                if (System.nanoTime()-start>15_000_000_000L) throw new ModelSettingsException(503,"DATASOURCE_CHECK_TIMEOUT","表结构检查耗时过长，请减少选表数量后重试。");
                try (var s=connection.createStatement()) {
                    s.setQueryTimeout(5); s.setMaxRows(1);
                    try (var r=s.executeQuery("SELECT * FROM `"+catalog+"`.`"+table+"` LIMIT 0")) {
                        var meta=r.getMetaData(); List<Column> columns=new ArrayList<>();
                        if (meta.getColumnCount()<1 || meta.getColumnCount()>200) throw new ModelSettingsException(400,"DATABASE_COLUMNS_LIMIT","业务表字段数量需在 1～200 之间。");
                        for (int i=1;i<=meta.getColumnCount();i++) columns.add(new Column(meta.getColumnName(i),meta.getColumnTypeName(i),meta.isNullable(i)!=ResultSetMetaData.columnNoNulls));
                        previews.add(new Table(table,List.copyOf(columns)));
                    }
                }
            }
            String privileges="UNKNOWN";
            try (var s=connection.createStatement()) {
                s.setQueryTimeout(5); s.setMaxRows(51);
                try (var r=s.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                    List<String> grants=new ArrayList<>();
                    while (r.next() && grants.size()<51) grants.add(r.getString(1));
                    if (grants.size()<51) privileges=DatabaseDiagnostics.classifyGrants(grants);
                }
            } catch (SQLException ignored) { }
            return new Result(account,catalog,privileges,List.copyOf(tables),truncated,List.copyOf(previews));
        } catch (SQLException ex) { throw failed(); }
    }
    private static ModelSettingsException failed() {
        return new ModelSettingsException(503,"DATABASE_CANDIDATE_FAILED","候选连接或表读取失败，请核对服务地址、密码、TLS 和 SELECT 权限。");
    }
}
