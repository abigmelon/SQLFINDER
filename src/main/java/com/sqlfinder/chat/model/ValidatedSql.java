package com.sqlfinder.chat.model;

/**
 * 已校验SQL
 */
public record ValidatedSql(String sql, SqlValidationSummary validation) { }
