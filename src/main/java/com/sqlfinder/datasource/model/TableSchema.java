package com.sqlfinder.datasource.model;

import java.util.List;

/**
 * 表示一张表
 * @param catalog
 * @param tableName
 * @param comment
 * @param columns
 */
public record TableSchema(
        String catalog,
        String tableName,
        String comment,
        List<ColumnSchema> columns
) {
}
