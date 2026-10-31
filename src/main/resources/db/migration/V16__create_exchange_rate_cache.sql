CREATE TABLE exchange_rate_state (
    singleton_id SMALLINT PRIMARY KEY,
    fetched_at TIMESTAMPTZ,

    CONSTRAINT chk_exchange_rate_state_singleton
        CHECK (singleton_id = 1)
);

INSERT INTO exchange_rate_state (singleton_id)
VALUES (1);

CREATE TABLE exchange_rate (
    currency_code VARCHAR(3) PRIMARY KEY,
    rate NUMERIC NOT NULL,
    rate_date DATE NOT NULL,

    CONSTRAINT chk_exchange_rate_currency_code
        CHECK (currency_code ~ '^[A-Z]{3}$'),

    CONSTRAINT chk_exchange_rate_positive
        CHECK (rate > 0),

    CONSTRAINT chk_exchange_rate_eur
        CHECK (currency_code <> 'EUR' OR rate = 1)
);