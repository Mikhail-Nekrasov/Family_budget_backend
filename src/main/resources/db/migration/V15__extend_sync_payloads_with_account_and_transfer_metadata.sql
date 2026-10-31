CREATE OR REPLACE VIEW sync_personal_entity AS
SELECT a.user_id, 'ACCOUNT'::VARCHAR(30) AS entity_type, a.sync_id,
    jsonb_build_object(
        'id', a.account_id,
        'syncId', a.sync_id,
        'version', a.version,
        'name', a.name,
        'amount', a.amount,
        'description', a.description,
        'currencyCode', a.currency_code,
        'iconKey', a.icon_key,
        'archivedAt', a.archived_at,
        'shared', EXISTS (
            SELECT 1
            FROM group_account_access g
            WHERE g.account_id = a.account_id
        )
    ) AS payload
FROM account a

UNION ALL

SELECT owners.user_id, 'CATEGORY', c.sync_id,
    jsonb_build_object(
        'id', c.category_id,
        'syncId', c.sync_id,
        'version', c.version,
        'name', c.name,
        'type', c.type,
        'readOnly', c.user_id IS DISTINCT FROM owners.user_id
    )
FROM category c
CROSS JOIN LATERAL (
    SELECT c.user_id WHERE c.user_id IS NOT NULL
    UNION
    SELECT e.user_id
    FROM entry e
    WHERE e.category_id = c.category_id
) AS owners(user_id)

UNION ALL

SELECT e.user_id, 'ENTRY', e.sync_id,
    jsonb_build_object(
        'id', e.id,
        'syncId', e.sync_id,
        'version', e.version,
        'title', e.title,
        'type', e.type,
        'amount', e.amount,
        'dateTime', e.date_time,
        'description', e.description,
        'accountSyncId', a.sync_id,
        'categorySyncId', c.sync_id
    )
FROM entry e
JOIN account a ON a.account_id = e.account_id
JOIN category c ON c.category_id = e.category_id

UNION ALL

SELECT owners.user_id, 'TRANSFER', t.sync_id,
    jsonb_build_object(
        'id', t.id,
        'syncId', t.sync_id,
        'amount', t.amount,
        'targetAmount', t.target_amount,
        'sourceCurrencyCode', t.source_currency_code,
        'targetCurrencyCode', t.target_currency_code,
        'dateTime', t.date_time,
        'description', t.description,
        'fromAccountSyncId', f.sync_id,
        'toAccountSyncId', dest.sync_id,
        'fromAccountName', f.name,
        'toAccountName', dest.name,
        'fromAccountOwned', f.user_id = owners.user_id,
        'toAccountOwned', dest.user_id = owners.user_id
    )
FROM transfer t
JOIN account f ON f.account_id = t.from_account_id
JOIN account dest ON dest.account_id = t.to_account_id
CROSS JOIN LATERAL (
    SELECT f.user_id
    UNION
    SELECT dest.user_id
) AS owners(user_id);

-- Publish the extended payloads to existing synchronization clients,
-- even when the underlying accounts and transfers have not changed.
INSERT INTO sync_change (user_id, entity_type, sync_id)
SELECT user_id, entity_type, sync_id
FROM sync_personal_entity
WHERE entity_type IN ('ACCOUNT', 'TRANSFER');