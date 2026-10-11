package com.sqlfinder.chat.api;

import com.sqlfinder.chat.model.SqlValidationSummary;
import java.util.List;
import java.util.Map;

/**
 * 问数响应
 */
public record AnswerResponse(
        String status,
        String question,
        String sql,
        List<String> columns,
        List<Map<String, Object>> rows,
        String message,
        SqlValidationSummary validation,
        String errorCode,
        boolean truncated) {

    public AnswerResponse(String status, String question, String sql,
                          List<String> columns, List<Map<String, Object>> rows, String message) {
        this(status, question, sql, columns, rows, message, null, null, false);
    }

    public AnswerResponse(String status, String question, String sql,
                          List<String> columns, List<Map<String, Object>> rows, String message,
                          SqlValidationSummary validation, String errorCode) {
        this(status, question, sql, columns, rows, message, validation, errorCode, false);
    }
}