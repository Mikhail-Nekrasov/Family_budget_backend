CREATE TABLE sync_tombstone (
    user_id BIGINT NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    sync_id UUID NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_sync_tombstone
        PRIMARY KEY (user_id, entity_type, sync_id),

    CONSTRAINT fk_sync_tombstone_user
        FOREIGN KEY (user_id)
        REFERENCES users (id),

    CONSTRAINT chk_sync_tombstone_entity_type
        CHECK (entity_type IN ('ACCOUNT', 'CATEGORY', 'ENTRY', 'TRANSFER'))
);