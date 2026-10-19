CREATE TABLE sync_command (
    user_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    operation VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status SMALLINT NOT NULL,
    response_body TEXT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_sync_command
        PRIMARY KEY (user_id, command_id),

    CONSTRAINT fk_sync_command_user
        FOREIGN KEY (user_id)
        REFERENCES users (id),

    CONSTRAINT chk_sync_command_response_status
        CHECK (response_status BETWEEN 200 AND 299)
);