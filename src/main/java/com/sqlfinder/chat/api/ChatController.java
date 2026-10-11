package com.sqlfinder.chat.api;

import com.sqlfinder.chat.application.QuestionOrchestrator;
import com.sqlfinder.chat.application.SqlPolicy;
import com.sqlfinder.chat.config.QueryExecutionProperties;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.security.Principal;

/**
 * 问数接口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final QuestionOrchestrator orchestrator;
    private final QueryExecutionProperties queryProperties;

    @Autowired
    public ChatController(QuestionOrchestrator orchestrator,
                          QueryExecutionProperties queryProperties) {
        this.orchestrator = orchestrator;
        this.queryProperties = queryProperties;
    }

    @PostMapping("/question")
    public ResponseEntity<AnswerResponse> ask(@Valid @RequestBody QuestionRequest request,
                                            Principal principal) {
        return ResponseEntity.ok(orchestrator.ask(principal.getName(), request));
    }

    @GetMapping("/sql-policy")
    public Map<String, Object> sqlPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>(SqlPolicy.describe());
        policy.put("executionEnabled", queryProperties.enabled());
        policy.put("queryTimeoutSeconds", queryProperties.timeoutSeconds());
        policy.put("maxResultRows", queryProperties.maxRows());
        return policy;
    }
}
