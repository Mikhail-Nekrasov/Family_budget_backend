-- Immutable download pages and commit-safe checkpoints for PostgreSQL 17+.
-- A sequence number alone is NOT a safe checkpoint: transactions may commit out of order.
CREATE TABLE sync_change (
    change_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity_type VARCHAR(30) NOT NULL
        CHECK (entity_type IN ('ACCOUNT', 'CATEGORY', 'ENTRY', 'TRANSFER')),
    sync_id UUID NOT NULL,
    transaction_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_sync_change_user ON sync_change(user_id, change_id);
CREATE INDEX idx_sync_change_time ON sync_change(occurred_at);

CREATE TABLE sync_download (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    visibility_snapshot TEXT NOT NULL,
    mode VARCHAR(5) NOT NULL CHECK (mode IN ('FULL', 'DELTA')),
    item_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    pages_expire_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '1 day'),
    cursor_expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '7 days')
);
CREATE INDEX idx_sync_download_user ON sync_download(user_id, created_at);

CREATE TABLE sync_download_item (
    download_id UUID NOT NULL REFERENCES sync_download(id) ON DELETE CASCADE,
    position BIGINT NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    sync_id UUID NOT NULL,
    action VARCHAR(6) NOT NULL CHECK (action IN ('UPSERT', 'DELETE')),
    payload JSONB,
    PRIMARY KEY (download_id, position)
);

-- Only the owner's data is downloaded. Foreign accounts' balances and group members
-- are never included. Referenced categories are included as read-only dependencies.
CREATE VIEW sync_personal_entity AS
SELECT a.user_id, 'ACCOUNT'::VARCHAR(30) AS entity_type, a.sync_id,
    jsonb_build_object(
        'id', a.account_id, 'syncId', a.sync_id, 'version', a.version,
        'name', a.name, 'amount', a.amount, 'description', a.description,
        'shared', EXISTS (SELECT 1 FROM group_account_access g WHERE g.account_id = a.account_id)
    ) AS payload
FROM account a
UNION ALL
SELECT owners.user_id, 'CATEGORY', c.sync_id,
    jsonb_build_object(
        'id', c.category_id, 'syncId', c.sync_id, 'version', c.version,
        'name', c.name, 'type', c.type,
        'readOnly', c.user_id IS DISTINCT FROM owners.user_id
    )
FROM category c
CROSS JOIN LATERAL (
    SELECT c.user_id WHERE c.user_id IS NOT NULL
    UNION
    SELECT e.user_id FROM entry e WHERE e.category_id = c.category_id
) AS owners(user_id)
UNION ALL
SELECT e.user_id, 'ENTRY', e.sync_id,
    jsonb_build_object(
        'id', e.id, 'syncId', e.sync_id, 'version', e.version,
        'title', e.title, 'type', e.type, 'amount', e.amount,
        'dateTime', e.date_time, 'description', e.description,
        'accountSyncId', a.sync_id, 'categorySyncId', c.sync_id
    )
FROM entry e
JOIN account a ON a.account_id = e.account_id
JOIN category c ON c.category_id = e.category_id
UNION ALL
SELECT owners.user_id, 'TRANSFER', t.sync_id,
    jsonb_build_object(
        'id', t.id, 'syncId', t.sync_id, 'amount', t.amount,
        'dateTime', t.date_time, 'description', t.description,
        'fromAccountSyncId', f.sync_id, 'toAccountSyncId', dest.sync_id,
        'fromAccountName', f.name, 'toAccountName', dest.name,
        'fromAccountOwned', f.user_id = owners.user_id,
        'toAccountOwned', dest.user_id = owners.user_id
    )
FROM transfer t
JOIN account f ON f.account_id = t.from_account_id
JOIN account dest ON dest.account_id = t.to_account_id
CROSS JOIN LATERAL (
    SELECT f.user_id UNION SELECT dest.user_id
) AS owners(user_id);

CREATE FUNCTION sync_record_change(p_user BIGINT, p_type VARCHAR, p_sync UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_user IS NOT NULL THEN
        INSERT INTO sync_change(user_id, entity_type, sync_id)
        VALUES (p_user, p_type, p_sync);
    END IF;
END;
$$;

CREATE FUNCTION sync_track_entity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    old_row JSONB;
    new_row JSONB;
    row_data JSONB;
    row_owner BIGINT;
    related_owner BIGINT;
    related_sync UUID;
    source_id BIGINT;
    target_id BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_row := to_jsonb(OLD); END IF;
    IF TG_OP <> 'DELETE' THEN new_row := to_jsonb(NEW); END IF;

    -- Log both old and new visibility. This also handles a future ownership change.
    FOR row_data IN
        SELECT old_row WHERE old_row IS NOT NULL AND (
            TG_OP = 'DELETE'
            OR old_row->>'user_id' IS DISTINCT FROM new_row->>'user_id'
            OR (TG_TABLE_NAME = 'entry' AND old_row->>'category_id' IS DISTINCT FROM new_row->>'category_id')
            OR (TG_TABLE_NAME = 'transfer' AND (
                old_row->>'from_account_id' IS DISTINCT FROM new_row->>'from_account_id'
                OR old_row->>'to_account_id' IS DISTINCT FROM new_row->>'to_account_id'
            ))
        )
        UNION ALL SELECT new_row WHERE new_row IS NOT NULL
    LOOP
        IF TG_TABLE_NAME IN ('account', 'category', 'entry') THEN
            row_owner := (row_data->>'user_id')::BIGINT;
            PERFORM sync_record_change(row_owner, upper(TG_TABLE_NAME), (row_data->>'sync_id')::UUID);
        END IF;

        IF TG_TABLE_NAME = 'entry' THEN
            -- A referenced foreign category enters/leaves this user's offline dataset.
            SELECT sync_id INTO related_sync FROM category
                WHERE category_id = (row_data->>'category_id')::BIGINT;
            IF related_sync IS NOT NULL THEN
                PERFORM sync_record_change(row_owner, 'CATEGORY', related_sync);
            END IF;
        ELSIF TG_TABLE_NAME = 'category' THEN
            FOR related_owner IN SELECT DISTINCT user_id FROM entry
                WHERE category_id = (row_data->>'category_id')::BIGINT
            LOOP
                PERFORM sync_record_change(related_owner, 'CATEGORY', (row_data->>'sync_id')::UUID);
            END LOOP;
        ELSIF TG_TABLE_NAME = 'transfer' THEN
            source_id := (row_data->>'from_account_id')::BIGINT;
            target_id := (row_data->>'to_account_id')::BIGINT;
            FOR related_owner IN SELECT DISTINCT user_id FROM account
                WHERE account_id IN (source_id, target_id)
            LOOP
                PERFORM sync_record_change(related_owner, 'TRANSFER', (row_data->>'sync_id')::UUID);
            END LOOP;
        END IF;
    END LOOP;

    -- Transfer display names depend on account names; publish the dependent rows too.
    IF TG_TABLE_NAME = 'account' AND TG_OP = 'UPDATE'
            AND old_row->>'name' IS DISTINCT FROM new_row->>'name' THEN
        FOR related_sync, related_owner IN
            SELECT DISTINCT t.sync_id, a.user_id
            FROM transfer t
            JOIN account a ON a.account_id IN (t.from_account_id, t.to_account_id)
            WHERE (new_row->>'account_id')::BIGINT IN (t.from_account_id, t.to_account_id)
        LOOP
            PERFORM sync_record_change(related_owner, 'TRANSFER', related_sync);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER sync_account_changes AFTER INSERT OR UPDATE OR DELETE ON account
    FOR EACH ROW EXECUTE FUNCTION sync_track_entity();
CREATE TRIGGER sync_category_changes AFTER INSERT OR UPDATE OR DELETE ON category
    FOR EACH ROW EXECUTE FUNCTION sync_track_entity();
CREATE TRIGGER sync_entry_changes AFTER INSERT OR UPDATE OR DELETE ON entry
    FOR EACH ROW EXECUTE FUNCTION sync_track_entity();
CREATE TRIGGER sync_transfer_changes AFTER INSERT OR UPDATE OR DELETE ON transfer
    FOR EACH ROW EXECUTE FUNCTION sync_track_entity();

CREATE FUNCTION sync_track_account_access() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_key BIGINT;
    account_owner BIGINT;
    account_sync UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN account_key := OLD.account_id;
    ELSE account_key := NEW.account_id;
    END IF;
    SELECT user_id, sync_id INTO account_owner, account_sync FROM account
        WHERE account_id = account_key;
    IF account_sync IS NOT NULL THEN
        PERFORM sync_record_change(account_owner, 'ACCOUNT', account_sync);
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.account_id <> NEW.account_id THEN
        SELECT user_id, sync_id INTO account_owner, account_sync FROM account
            WHERE account_id = OLD.account_id;
        IF account_sync IS NOT NULL THEN
            PERFORM sync_record_change(account_owner, 'ACCOUNT', account_sync);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER sync_group_access_changes AFTER INSERT OR UPDATE OR DELETE ON group_account_access
    FOR EACH ROW EXECUTE FUNCTION sync_track_account_access();
