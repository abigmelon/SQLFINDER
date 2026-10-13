-- 会话历史结构
CREATE TABLE IF NOT EXISTS history_conversation (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL,
    last_activity_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS history_owner_index
    ON history_conversation(user_id, fingerprint, last_activity_at);
CREATE TABLE IF NOT EXISTS history_turn (
    sequence_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    conversation_id UUID NOT NULL REFERENCES history_conversation(id),
    parent_turn_id UUID,
    regenerated_from UUID,
    request_mode VARCHAR(16) NOT NULL,
    answer_json CLOB NOT NULL,
    row_count INTEGER NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS history_turn_index ON history_turn(conversation_id, sequence_id);
