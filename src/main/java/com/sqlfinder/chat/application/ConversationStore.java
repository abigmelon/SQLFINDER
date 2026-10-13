package com.sqlfinder.chat.application;

import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.ConversationRequestException;
import com.sqlfinder.chat.api.QuestionRequest;
import com.sqlfinder.chat.api.RequestMode;
import com.sqlfinder.chat.application.ConversationRepository.Conversation;
import com.sqlfinder.chat.application.ConversationRepository.Turn;
import com.sqlfinder.chat.model.ConversationPromptContext;
import com.sqlfinder.chat.model.ConversationTurnContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 会话上下文管理 */
@Component
public class ConversationStore {
    static final int MAX_CONTEXT_TURNS = 5;
    static final Duration TTL = Duration.ofMinutes(30);
    private static final int PAGE_SIZE = 50;
    private final ConversationRepository repository;
    private final Clock clock;

    @Autowired
    public ConversationStore(ConversationRepository repository) { this(repository, Clock.systemUTC()); }

    public ConversationStore(ConversationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Resolution resolve(String userId, QuestionRequest request, String fingerprint) {
        RequestMode mode = request.mode();
        if (mode == RequestMode.NEW) {
            Instant now = clock.instant();
            String title = request.question().strip();
            title = title.substring(0, title.offsetByCodePoints(0, Math.min(60, title.codePointCount(0, title.length()))));
            Conversation conversation = new Conversation(UUID.randomUUID(), userId, fingerprint, title, now, now);
            return new Resolution(conversation, null, null, mode, request.question(), ConversationPromptContext.empty());
        }
        Turn parent = repository.ownedTurn(request.resource(), userId).orElseThrow(ConversationStore::notFound);
        Conversation conversation = authorized(userId, parent.conversationId(), fingerprint);
        if (!conversation.lastActivityAt().plus(TTL).isAfter(clock.instant())) {
            throw new ConversationRequestException("前置上下文已过期，历史仍保留，请开始新对话。", "CONTEXT_EXPIRED");
        }
        String question = mode == RequestMode.REGENERATE ? parent.answer().question() : request.question();
        // Regeneration does not feed the source answer back as prior context.
        Turn contextParent = mode == RequestMode.REGENERATE
                ? (parent.parentTurnId() == null ? null : repository.turn(parent.parentTurnId(), conversation.id()).orElse(null))
                : parent;
        return new Resolution(conversation, parent.id(), mode == RequestMode.REGENERATE ? parent.id() : null,
                mode, question, contextFor(conversation, contextParent));
    }

    public AnswerResponse append(Resolution resolution, AnswerResponse answer) {
        UUID id = UUID.randomUUID();
        AnswerResponse identified = answer.withConversation(resolution.conversationId(), id,
                resolution.parentTurnId(), resolution.mode());
        repository.append(resolution.conversation(), new Turn(id, resolution.conversationId(),
                resolution.parentTurnId(), resolution.regeneratedFrom(), resolution.mode(), identified,
                answer.rows().size(), clock.instant()));
        return identified;
    }

    public Page<ConversationSummary> list(String userId, String fingerprint, int offset) {
        checkOffset(offset);
        List<Conversation> conversations = repository.conversations(userId, fingerprint, offset, PAGE_SIZE + 1);
        return new Page<>(conversations.stream().limit(PAGE_SIZE)
                .map(c -> new ConversationSummary(c.id(), c.title(), c.createdAt(), c.lastActivityAt())).toList(),
                conversations.size() > PAGE_SIZE, offset + Math.min(conversations.size(), PAGE_SIZE));
    }

    public History detail(String userId, UUID id, String fingerprint, int offset) {
        checkOffset(offset);
        Conversation conversation = authorized(userId, id, fingerprint);
        List<Turn> fetched = repository.turns(id, offset, PAGE_SIZE + 1);
        List<Turn> visible = new ArrayList<>(fetched.stream().limit(PAGE_SIZE).toList());
        Collections.reverse(visible);
        return new History(id, conversation.title(), conversation.lastActivityAt().plus(TTL).isAfter(clock.instant()),
                visible, fetched.size() > PAGE_SIZE, offset + visible.size());
    }

    private Conversation authorized(String userId, UUID id, String fingerprint) {
        Conversation conversation = repository.conversation(id, userId).orElseThrow(ConversationStore::notFound);
        if (!conversation.fingerprint().equals(fingerprint)) {
            throw new ConversationRequestException("当前授权表结构与历史会话不同，请开始新对话。", "CONTEXT_SCHEMA_CHANGED");
        }
        return conversation;
    }

    private ConversationPromptContext contextFor(Conversation conversation, Turn parent) {
        List<ConversationTurnContext> chain = new ArrayList<>();
        Turn current = parent;
        while (current != null && chain.size() < MAX_CONTEXT_TURNS) {
            AnswerResponse answer = current.answer();
            chain.add(new ConversationTurnContext(current.id(), answer.question(), answer.sql(),
                    answer.validation() == null ? List.of() : answer.validation().tables(), answer.columns(),
                    current.rowCount(), answer.truncated(), answer.status()));
            current = current.parentTurnId() == null ? null : repository.turn(current.parentTurnId(), conversation.id()).orElse(null);
        }
        Collections.reverse(chain);
        return new ConversationPromptContext(conversation.id(), chain);
    }

    private static ConversationRequestException notFound() {
        return new ConversationRequestException("会话不存在或不属于当前用户。", "CONTEXT_NOT_FOUND");
    }

    private static void checkOffset(int offset) {
        if (offset < 0 || offset > 1_000_000) {
            throw new ConversationRequestException("历史分页参数不正确。", "INVALID_ARGUMENT");
        }
    }

    public record Resolution(Conversation conversation, UUID parentTurnId, UUID regeneratedFrom,
                             RequestMode mode, String question, ConversationPromptContext context) {
        public UUID conversationId() { return conversation.id(); }
    }
    public record ConversationSummary(UUID id, String title, Instant createdAt, Instant lastActivityAt) { }
    public record Page<T>(List<T> items, boolean hasMore, int nextOffset) { }
    public record History(UUID conversationId, String title, boolean contextAvailable, List<Turn> turns,
                          boolean hasMore, int nextOffset) { }
}
