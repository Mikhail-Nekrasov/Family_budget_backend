ALTER TABLE transfer
    ADD COLUMN target_amount NUMERIC(38, 2),
    ADD COLUMN source_currency_code VARCHAR(3),
    ADD COLUMN target_currency_code VARCHAR(3);

UPDATE transfer AS transfers
SET
    target_amount = transfers.amount,
    source_currency_code = source.currency_code,
    target_currency_code = destination.currency_code
FROM account AS source, account AS destination
WHERE source.account_id = transfers.from_account_id
  AND destination.account_id = transfers.to_account_id;

ALTER TABLE transfer
    ALTER COLUMN target_amount SET NOT NULL,
    ALTER COLUMN source_currency_code SET NOT NULL,
    ALTER COLUMN target_currency_code SET NOT NULL;

ALTER TABLE transfer
    ADD CONSTRAINT chk_transfer_target_amount
        CHECK (target_amount > 0),
    ADD CONSTRAINT chk_transfer_source_currency
        CHECK (source_currency_code ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT chk_transfer_target_currency
        CHECK (target_currency_code ~ '^[A-Z]{3}$');