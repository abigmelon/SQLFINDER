package com.sqlfinder.chat.application;

import com.sqlfinder.chat.api.AnswerResponse;
import com.sqlfinder.chat.api.RequestMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话存储接口 */
public interface ConversationRepository {
    Optional<Conversation> conversation(UUID id, String userId);
    Optional<Turn> turn(UUID id, UUID conversationId);
    Optional<Turn> ownedTurn(UUID id, String userId);
    List<Conversation> conversations(String userId, String fingerprint, int offset, int limit);
    List<Turn> turns(UUID conversationId, int offset, int limit);
    void append(Conversation conversation, Turn turn);

    record Conversation(UUID id, String userId, String fingerprint, String title,
                        Instant createdAt, Instant lastActivityAt) { }
    record Turn(UUID id, UUID conversationId, UUID parentTurnId, UUID regeneratedFrom,
                RequestMode mode, AnswerResponse answer, int rowCount, Instant createdAt, boolean resultSaved) {
        public Turn(UUID id, UUID conversationId, UUID parentTurnId, UUID regeneratedFrom,
                    RequestMode mode, AnswerResponse answer, int rowCount, Instant createdAt) {
            this(id, conversationId, parentTurnId, regeneratedFrom, mode, answer, rowCount, createdAt, true);
        }
    }
}
