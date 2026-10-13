package com.sqlfinder.datasource.model;

/** 字段结构 */
public record ColumnSchema(
        String name,
        String dataType,
        boolean nullable,
        String comment
) {
}
