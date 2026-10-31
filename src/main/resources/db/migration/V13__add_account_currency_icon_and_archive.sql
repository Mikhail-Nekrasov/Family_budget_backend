ALTER TABLE account
    ADD COLUMN currency_code VARCHAR(3) NOT NULL DEFAULT 'EUR',
    ADD COLUMN icon_key VARCHAR(64) NOT NULL DEFAULT 'cash',
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE account
    ADD CONSTRAINT chk_account_currency_code
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT chk_account_icon_key
        CHECK (length(trim(icon_key)) > 0);