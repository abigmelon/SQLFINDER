package com.sqlfinder.chat.api;

import com.sqlfinder.chat.application.ConversationStore;
import com.sqlfinder.chat.application.SchemaFingerprint;
import com.sqlfinder.datasource.application.UserMetadataService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.UUID;

/** 历史会话接口 */
@RestController
@RequestMapping("/api/chat/conversations")
public class ConversationController {
    private final ConversationStore store;
    private final UserMetadataService metadata;
    private final SchemaFingerprint fingerprint;

    public ConversationController(ConversationStore store, UserMetadataService metadata, SchemaFingerprint fingerprint) {
        this.store = store;
        this.metadata = metadata;
        this.fingerprint = fingerprint;
    }

    @GetMapping
    public ResponseEntity<ConversationStore.Page<ConversationStore.ConversationSummary>> list(
            Principal principal, @RequestParam(defaultValue = "0") int offset) {
        String scope = fingerprint.of(metadata.getAuthorizedSchemas(principal.getName()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(principal.getName(), scope, offset));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationStore.History> detail(Principal principal, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset) {
        String scope = fingerprint.of(metadata.getAuthorizedSchemas(principal.getName()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.detail(principal.getName(), id, scope, offset));
    }
}
