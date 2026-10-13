package com.sqlfinder.chat.api;

import com.sqlfinder.chat.application.QuestionOrchestrator;
import com.sqlfinder.chat.application.SqlPolicy;
import com.sqlfinder.chat.application.RequestExecution;
import com.sqlfinder.chat.application.RequestStoppedException;
import com.sqlfinder.chat.application.WorkflowRegistry;
import com.sqlfinder.chat.application.WorkflowRun;
import com.sqlfinder.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.security.Principal;
import java.util.UUID;

/** 问数接口 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final QuestionOrchestrator orchestrator;
    private final QueryExecutionProperties queryProperties;
    private WorkflowRegistry workflows = new WorkflowRegistry();

    @Autowired
    public ChatController(QuestionOrchestrator orchestrator,
                          QueryExecutionProperties queryProperties) {
        this.orchestrator = orchestrator;
        this.queryProperties = queryProperties;
    }

    @Autowired(required = false)
    public void workflows(WorkflowRegistry workflows) {
        this.workflows.close();
        this.workflows = workflows;
    }

    @PostMapping("/question")
    public ResponseEntity<AnswerResponse> ask(@Valid @RequestBody QuestionRequest request,
                                            Principal principal, HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        UUID id = RequestIdFilter.identify(httpRequest);
        httpResponse.setHeader(RequestIdFilter.HEADER, id.toString());
        return askTracked(request, principal, id);
    }

    public ResponseEntity<AnswerResponse> ask(QuestionRequest request, Principal principal) {
        return askTracked(request, principal, UUID.randomUUID());
    }

    private ResponseEntity<AnswerResponse> askTracked(QuestionRequest request, Principal principal, UUID id) {
        RequestExecution execution = workflows.begin(id, principal.getName());
        try (var scope = execution.bind()) {
            AnswerResponse answer = orchestrator.ask(principal.getName(), request);
            execution.check();
            answer = answer.withRequestId(id);
            execution.result(answer.status(), answer.errorCode(), answer.conversationId(), answer.turnId());
            return ResponseEntity.ok().header(RequestIdFilter.HEADER, id.toString()).body(answer);
        } catch (RequestStoppedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            execution.check();
            String code = ex instanceof com.sqlfinder.common.application.QuestionProcessingException processing
                    ? switch (processing.getStage()) {
                        case METADATA -> "METADATA_UNAVAILABLE";
                        case PROMPT -> "PROMPT_BUILD_FAILED";
                        case MODEL_REQUEST -> "MODEL_REQUEST_FAILED";
                        case MODEL_OUTPUT -> "MODEL_OUTPUT_INVALID";
                    } : ex instanceof com.sqlfinder.chat.application.HistoryStorageException
                    ? "HISTORY_UNAVAILABLE" : ex instanceof ConversationRequestException context
                    ? context.code() : ex instanceof com.sqlfinder.auth.application.TableAccessDeniedException
                    ? "TABLE_ACCESS_DENIED" : "INTERNAL_ERROR";
            execution.result("FAILED", code, null, null);
            throw ex;
        } finally { workflows.finish(execution); }
    }

    @GetMapping("/requests/{id}")
    public WorkflowRun requestStatus(@PathVariable UUID id, Principal principal) {
        return workflows.find(id, principal.getName());
    }

    @PostMapping("/requests/{id}/cancel")
    public WorkflowRun cancel(@PathVariable UUID id, Principal principal) {
        return workflows.cancel(id, principal.getName());
    }

    @GetMapping("/sql-policy")
    public Map<String, Object> sqlPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>(SqlPolicy.describe());
        policy.put("executionEnabled", queryProperties.enabled());
        policy.put("queryTimeoutSeconds", queryProperties.timeoutSeconds());
        policy.put("maxResultRows", queryProperties.maxRows());
        policy.put("requestTimeoutSeconds", workflows.timeoutSeconds());
        return policy;
    }
}
