package com.sqlfinder.chat.api;

import com.sqlfinder.chat.model.SqlValidationSummary;
import com.sqlfinder.chat.model.ResultColumn;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 问数响应 */
public record AnswerResponse(
        String status,
        String question,
        String sql,
        List<String> columns,
        List<Map<String, Object>> rows,
        String message,
        SqlValidationSummary validation,
        String errorCode,
        boolean truncated,
        List<ResultColumn> columnMetadata,
        UUID conversationId,
        UUID turnId,
        UUID parentTurnId,
        RequestMode mode,
        UUID requestId) {

    public AnswerResponse(String status, String question, String sql, List<String> columns,
                          List<Map<String, Object>> rows, String message, SqlValidationSummary validation,
                          String errorCode, boolean truncated, List<ResultColumn> columnMetadata,
                          UUID conversationId, UUID turnId, UUID parentTurnId, RequestMode mode) {
        this(status, question, sql, columns, rows, message, validation, errorCode, truncated,
                columnMetadata, conversationId, turnId, parentTurnId, mode, null);
    }

    public AnswerResponse(String status,String question,String sql,List<String> columns,List<Map<String,Object>> rows,
                          String message,SqlValidationSummary validation,String errorCode,boolean truncated) {
        this(status,question,sql,columns,rows,message,validation,errorCode,truncated,List.of(),null,null,null,null);
    }

    public AnswerResponse(String status, String question, String sql,
                          List<String> columns, List<Map<String, Object>> rows, String message) {
        this(status, question, sql, columns, rows, message, null, null, false,List.of(),null,null,null,null);
    }

    public AnswerResponse(String status, String question, String sql,
                          List<String> columns, List<Map<String, Object>> rows, String message,
                          SqlValidationSummary validation, String errorCode) {
        this(status, question, sql, columns, rows, message, validation, errorCode, false,List.of(),null,null,null,null);
    }

    public AnswerResponse(String status, String question, String sql,
                          List<String> columns, List<Map<String, Object>> rows, String message,
                          SqlValidationSummary validation, String errorCode, boolean truncated,
                          List<ResultColumn> columnMetadata) {
        this(status, question, sql, columns, rows, message, validation, errorCode, truncated,
                columnMetadata, null, null, null, null);
    }

    public AnswerResponse withConversation(UUID conversationId, UUID turnId,
                                           UUID parentTurnId, RequestMode mode) {
        return new AnswerResponse(status, question, sql, columns, rows, message, validation,
                errorCode, truncated, columnMetadata, conversationId, turnId, parentTurnId, mode, requestId);
    }

    public AnswerResponse withRequestId(UUID id) {
        return new AnswerResponse(status, question, sql, columns, rows, message, validation, errorCode,
                truncated, columnMetadata, conversationId, turnId, parentTurnId, mode, id);
    }
}
