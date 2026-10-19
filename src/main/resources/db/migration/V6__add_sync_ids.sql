ALTER TABLE account
    ADD COLUMN sync_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE account
    ADD CONSTRAINT uq_account_sync_id UNIQUE (sync_id);


ALTER TABLE category
    ADD COLUMN sync_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE category
    ADD CONSTRAINT uq_category_sync_id UNIQUE (sync_id);


ALTER TABLE entry
    ADD COLUMN sync_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE entry
    ADD CONSTRAINT uq_entry_sync_id UNIQUE (sync_id);


ALTER TABLE transfer
    ADD COLUMN sync_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE transfer
    ADD CONSTRAINT uq_transfer_sync_id UNIQUE (sync_id);