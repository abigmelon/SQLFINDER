package com.sqlfinder.datasource.model;

/**
 * 表示一个字段
 * @param name
 * @param dataType
 * @param nullable
 * @param comment
 */
public record ColumnSchema(
        String name,
        String dataType,
        boolean nullable,
        String comment
) {
}
