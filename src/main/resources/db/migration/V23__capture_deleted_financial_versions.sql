CREATE FUNCTION capture_deleted_financial_version() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    snapshot JSONB := to_jsonb(OLD);
    owner_account_id BIGINT := COALESCE((snapshot->>'account_id')::bigint, (snapshot->>'from_account_id')::bigint);
BEGIN
    IF EXISTS (
        SELECT 1 FROM group_account_access
        WHERE account_id IN ((snapshot->>'account_id')::bigint,
                             (snapshot->>'from_account_id')::bigint,
                             (snapshot->>'to_account_id')::bigint)
    ) THEN
        RETURN OLD;
    END IF;
    UPDATE sync_tombstone
    SET restore_snapshot = jsonb_strip_nulls(jsonb_build_object('version', snapshot->'version', 'type', snapshot->'type'))
    WHERE entity_type = upper(TG_TABLE_NAME) AND sync_id = OLD.sync_id AND NOT restored
      AND user_id = (SELECT user_id FROM account WHERE account_id = owner_account_id);
    RETURN OLD;
END;
$$;

CREATE TRIGGER capture_deleted_entry BEFORE DELETE ON entry
    FOR EACH ROW EXECUTE FUNCTION capture_deleted_financial_version();
CREATE TRIGGER capture_deleted_transfer BEFORE DELETE ON transfer
    FOR EACH ROW EXECUTE FUNCTION capture_deleted_financial_version();
