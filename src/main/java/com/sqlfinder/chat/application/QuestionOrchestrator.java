package com.sqlfinder.chat.application;

import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.PromptMessages;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.SqlGenerationResult;
import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.sqlfinder.common.application.QuestionProcessingException;
import com.sqlfinder.common.application.QuestionProcessingException.Stage;

import java.util.List;

/**
 * 问数编排器
 */
@Service
public class QuestionOrchestrator {
    private final UserMetadataService metadataService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ModelOutputParser modelOutputParser;
    private final SqlValidator sqlValidator;
    private final QueryExecutor queryExecutor;
    private final QueryExecutionProperties queryProperties;

    @Autowired
    public QuestionOrchestrator(
            UserMetadataService metadataService,
            PromptBuilder promptBuilder,
            LlmClient llmClient,
            ModelOutputParser modelOutputParser,
            SqlValidator sqlValidator,
            QueryExecutor queryExecutor,
            QueryExecutionProperties queryProperties) {
        this.metadataService = metadataService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.modelOutputParser = modelOutputParser;
        this.sqlValidator = sqlValidator;
        this.queryExecutor = queryExecutor;
        this.queryProperties = queryProperties;
    }

    public AnswerResponse ask(String userId, QuestionRequest request) {
        List<TableSchema> schemas;
        try {
            schemas = metadataService.getAuthorizedSchemas(userId);
        } catch (TableAccessDeniedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new QuestionProcessingException(Stage.METADATA, ex);
        }

        PromptMessages messages;
        try {
            messages = promptBuilder.build(request.question(), schemas);
        } catch (RuntimeException ex) {
            throw new QuestionProcessingException(Stage.PROMPT, ex);
        }

        String modelAnswer;
        try {
            modelAnswer = llmClient.generateSql(messages);
        } catch (RuntimeException ex) {
            throw new QuestionProcessingException(Stage.MODEL_REQUEST, ex);
        }

        SqlGenerationResult result;
        try {
            result = modelOutputParser.parse(modelAnswer);
        } catch (RuntimeException ex) {
            throw new QuestionProcessingException(Stage.MODEL_OUTPUT, ex);
        }

        if (!result.success()) {
            return new AnswerResponse("NEEDS_CLARIFICATION", request.question(), null,
                    List.of(), List.of(), result.message());
        }
        try {
            ValidatedSql candidate = sqlValidator.validate(result.sql(), schemas);
            if (!queryProperties.enabled()) {
                return new AnswerResponse("SQL_VALIDATED", request.question(), candidate.sql(),
                        List.of(), List.of(), "SQL 已通过当前只读查询规则检查，尚未执行。",
                        candidate.validation(), null);
            }
            try {
                QueryResult query = queryExecutor.execute(userId, candidate);
                String message = query.truncated()
                        ? "查询成功，结果已按返回行数上限截断。" : "查询成功。";
                return new AnswerResponse("QUERY_SUCCEEDED", request.question(), candidate.sql(),
                        query.columns(), query.rows(), message,
                        candidate.validation(), null, query.truncated());
            } catch (QueryExecutionException e) {
                return new AnswerResponse("QUERY_FAILED", request.question(), candidate.sql(),
                        List.of(), List.of(), e.getMessage(), candidate.validation(), e.getCode());
            }
        } catch (SqlValidationException e) {
            // 同时覆盖首次校验及执行器按最新授权进行的重新校验。
            return new AnswerResponse("SQL_REJECTED", request.question(), null,
                    List.of(), List.of(), e.getMessage(), null, e.getCode());
        }
    }
}
