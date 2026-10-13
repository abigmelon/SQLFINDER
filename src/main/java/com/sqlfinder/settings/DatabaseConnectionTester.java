package com.sqlfinder.settings;

import java.util.List;

/** 数据库连接测试接口 */
public interface DatabaseConnectionTester {
    Result inspect(DatabaseConfiguration candidate);
    record Column(String name, String type, boolean nullable) { }
    record Table(String name, List<Column> columns) { }
    record Result(String account, String catalog, String privileges, List<String> tables,
                  boolean truncated, List<Table> previews) { }
}
