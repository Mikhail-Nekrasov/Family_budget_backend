CREATE TABLE IF NOT EXISTS sync_tombstone (
    user_id BIGINT NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    sync_id UUID NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    restore_token UUID DEFAULT RANDOM_UUID() NOT NULL,
    restore_snapshot TEXT,
    restored BOOLEAN DEFAULT FALSE NOT NULL,

    PRIMARY KEY (user_id, entity_type, sync_id),

    CHECK (
        entity_type IN ('ACCOUNT', 'CATEGORY', 'ENTRY', 'TRANSFER')
    )
);