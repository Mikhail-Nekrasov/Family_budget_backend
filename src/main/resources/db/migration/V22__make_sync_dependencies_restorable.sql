ALTER TABLE sync_tombstone
    ADD COLUMN restore_token UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN restore_snapshot JSONB,
    ADD COLUMN restored BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION capture_sync_dependency_deletion() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    snapshot JSONB := to_jsonb(OLD);
BEGIN
    IF TG_TABLE_NAME = 'account' AND EXISTS (
        SELECT 1 FROM group_account_access
        WHERE account_id = (snapshot->>'account_id')::bigint
    ) THEN
        RETURN OLD;
    END IF;
    UPDATE sync_tombstone SET restore_snapshot = snapshot
    WHERE user_id = OLD.user_id AND entity_type = upper(TG_TABLE_NAME)
      AND sync_id = OLD.sync_id AND NOT restored;
    RETURN OLD;
END;
$$;

CREATE TRIGGER capture_deleted_account BEFORE DELETE ON account
    FOR EACH ROW EXECUTE FUNCTION capture_sync_dependency_deletion();
CREATE TRIGGER capture_deleted_category BEFORE DELETE ON category
    FOR EACH ROW EXECUTE FUNCTION capture_sync_dependency_deletion();
