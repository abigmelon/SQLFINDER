-- 问数运行记录
CREATE TABLE IF NOT EXISTS history_workflow_run (
    request_id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    run_json CLOB NOT NULL,
    started_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS history_workflow_owner ON history_workflow_run(user_id, started_at);
