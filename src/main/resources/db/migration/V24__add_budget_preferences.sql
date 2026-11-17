CREATE TABLE budget_preferences (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb
);
