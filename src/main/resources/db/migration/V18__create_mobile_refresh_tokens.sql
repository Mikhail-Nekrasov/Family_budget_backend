CREATE TABLE mobile_refresh_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_mobile_refresh_token_expiry ON mobile_refresh_token(expires_at);
