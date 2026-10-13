package com.sqlfinder.chat.application;

import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import com.sqlfinder.chat.model.PromptMessages;
import com.sqlfinder.chat.model.QueryResult;
import com.sqlfinder.chat.model.SqlGenerationResult;
import com.sqlfinder.chat.model.ValidatedSql;
import com.sqlfinder.chat.model.ConversationPromptContext;
import com.sqlfinder.datasource.application.UserMetadataService;
import com.sqlfinder.auth.application.TableAccessDeniedException;
import com.sqlfinder.datasource.model.TableSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.sqlfinder.common.application.QuestionProcessingException;
import com.sqlfinder.common.application.QuestionProcessingException.Stage;

import java.util.List;

/** 问数编排器 */
@Service
public class QuestionOrchestrator {
    private final UserMetadataService metadataService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ModelOutputParser modelOutputParser;
    private final SqlValidator sqlValidator;
    private final QueryExecutor queryExecutor;
    private final QueryExecutionProperties queryProperties;
    private final ConversationStore conversationStore;
    private final SchemaFingerprint schemaFingerprint;

    @Autowired
    public QuestionOrchestrator(
            UserMetadataService metadataService,
            PromptBuilder promptBuilder,
            LlmClient llmClient,
            ModelOutputParser modelOutputParser,
            SqlValidator sqlValidator,
            QueryExecutor queryExecutor,
            QueryExecutionProperties queryProperties,
            ConversationStore conversationStore,
            SchemaFingerprint schemaFingerprint) {
        this.metadataService = metadataService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.modelOutputParser = modelOutputParser;
        this.sqlValidator = sqlValidator;
        this.queryExecutor = queryExecutor;
        this.queryProperties = queryProperties;
        this.conversationStore = conversationStore;
        this.schemaFingerprint = schemaFingerprint;
    }

    public AnswerResponse ask(String userId, QuestionRequest request) {
        stage("REQUEST");
        request.mode();
        stage("METADATA");
        List<TableSchema> schemas;
        try {
            schemas = metadataService.getAuthorizedSchemas(userId);
        } catch (TableAccessDeniedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            check();
            throw new QuestionProcessingException(Stage.METADATA, ex);
        }

        stage("CONTEXT");
        ConversationStore.Resolution context = conversationStore.resolve(
                userId, request, schemaFingerprint.of(schemas));
        if (RequestExecution.current() != null) RequestExecution.current().conversation(context.conversationId());
        String question = context.question();

        PromptMessages messages;
        stage("PROMPT");
        try {
            messages = context.mode() == com.sqlfinder.chat.api.RequestMode.NEW
                    ? promptBuilder.build(question, schemas)
                    : promptBuilder.build(question, schemas, context.mode(), context.context());
        } catch (RuntimeException ex) {
            check();
            throw new QuestionProcessingException(Stage.PROMPT, ex);
        }

        String modelAnswer;
        stage("MODEL_REQUEST");
        try {
            modelAnswer = llmClient.generateSql(messages);
        } catch (RuntimeException ex) {
            if (ex instanceof RequestStoppedException stopped) throw stopped;
            check();
            throw new QuestionProcessingException(Stage.MODEL_REQUEST, ex);
        }

        SqlGenerationResult result;
        stage("MODEL_OUTPUT");
        try {
            result = modelOutputParser.parse(modelAnswer);
        } catch (RuntimeException ex) {
            check();
            throw new QuestionProcessingException(Stage.MODEL_OUTPUT, ex);
        }

        if (!result.success()) {
            AnswerResponse response = new AnswerResponse("NEEDS_CLARIFICATION", question, null,
                    List.of(), List.of(), result.message());
            return remember(context, question, response);
        }
        try {
            stage("VALIDATION");
            ValidatedSql candidate = sqlValidator.validate(result.sql(), schemas);
            if (!queryProperties.enabled()) {
                AnswerResponse response = new AnswerResponse("SQL_VALIDATED", question, candidate.sql(),
                        List.of(), List.of(), "SQL 已通过当前只读查询规则检查，尚未执行。",
                        candidate.validation(), null);
                return remember(context, question, response);
            }
            try {
                stage("EXECUTION");
                QueryResult query = queryExecutor.execute(userId, candidate);
                check();
                String message = query.truncated()
                        ? "查询成功，结果已按返回行数上限截断。" : "查询成功。";
                AnswerResponse response = new AnswerResponse("QUERY_SUCCEEDED", question, candidate.sql(),
                        query.columns(), query.rows(), message,
                        candidate.validation(), null, query.truncated(),query.columnMetadata());
                return remember(context, question, response);
            } catch (QueryExecutionException e) {
                check();
                failedAtCurrentStage();
                AnswerResponse response = new AnswerResponse("QUERY_FAILED", question, candidate.sql(),
                        List.of(), List.of(), e.getMessage(), candidate.validation(), e.getCode());
                return remember(context, question, response);
            }
        } catch (SqlValidationException e) {
            check();
            failedAtCurrentStage();
            // 同时覆盖首次校验及执行器按最新授权进行的重新校验。
            return remember(context, question, new AnswerResponse("SQL_REJECTED", question, null,
                    List.of(), List.of(), e.getMessage(), null, e.getCode()));
        }
    }

    private AnswerResponse remember(ConversationStore.Resolution context, String question,
                                    AnswerResponse response) {
        stage("HISTORY");
        RequestExecution execution = RequestExecution.current();
        if (execution != null) response = response.withRequestId(execution.requestId());
        return conversationStore.append(context, response);
    }

    private static void stage(String stage) {
        RequestExecution execution = RequestExecution.current();
        if (execution != null) execution.stage(stage);
    }
    private static void check() {
        RequestExecution execution = RequestExecution.current();
        if (execution != null) execution.check();
    }
    private static void failedAtCurrentStage() {
        RequestExecution execution = RequestExecution.current();
        if (execution != null) execution.failedAtCurrentStage();
    }
}
