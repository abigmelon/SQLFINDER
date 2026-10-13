package com.sqlfinder.datasource.model;

import java.util.List;

/** 表结构 */
public record TableSchema(
        String catalog,
        String tableName,
        String comment,
        List<ColumnSchema> columns
) {
}
