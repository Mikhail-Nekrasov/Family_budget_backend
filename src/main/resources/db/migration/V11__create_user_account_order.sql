CREATE TABLE user_account_order (
    user_id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_user_account_order_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_user_account_order_version
        CHECK (version >= 0)
);

CREATE TABLE user_account_order_item (
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,

    CONSTRAINT pk_user_account_order_item
        PRIMARY KEY (user_id, account_id),

    CONSTRAINT fk_user_account_order_item_order
        FOREIGN KEY (user_id)
        REFERENCES user_account_order(user_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_user_account_order_item_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_user_account_order_item_position
        CHECK (sort_order >= 0)
);

CREATE INDEX idx_user_account_order_item_position
    ON user_account_order_item(user_id, sort_order, account_id);

CREATE INDEX idx_user_account_order_item_account
    ON user_account_order_item(account_id);