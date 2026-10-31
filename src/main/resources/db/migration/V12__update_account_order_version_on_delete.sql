CREATE FUNCTION update_account_order_version_before_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE user_account_order AS orders
    SET version = orders.version + 1
    WHERE EXISTS (
        SELECT 1
        FROM user_account_order_item AS items
        WHERE items.user_id = orders.user_id
          AND items.account_id = OLD.account_id
    );

    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_account_order_version_before_delete
BEFORE DELETE ON account
FOR EACH ROW
EXECUTE FUNCTION update_account_order_version_before_delete();