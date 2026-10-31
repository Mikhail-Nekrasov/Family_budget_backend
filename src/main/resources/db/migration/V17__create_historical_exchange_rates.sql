CREATE TABLE historical_exchange_rate (
    requested_date DATE NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    rate NUMERIC NOT NULL,
    rate_date DATE NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_historical_exchange_rate
        PRIMARY KEY (requested_date, currency_code),

    CONSTRAINT chk_historical_exchange_rate_currency
        CHECK (
            currency_code ~ '^[A-Z]{3}$'
            AND currency_code <> 'EUR'
        ),

    CONSTRAINT chk_historical_exchange_rate_positive
        CHECK (rate > 0),

    CONSTRAINT chk_historical_exchange_rate_date
        CHECK (rate_date <= requested_date)
);