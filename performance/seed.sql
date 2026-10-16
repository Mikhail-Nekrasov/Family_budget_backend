\set ON_ERROR_STOP on

-- Refuse to populate any other database.
DO $$
BEGIN
    IF current_database() <> 'family_budget_load' THEN
        RAISE EXCEPTION 'This script requires family_budget_load';
    END IF;

    IF EXISTS (
        SELECT 1 FROM users WHERE username LIKE 'perf_user_%'
    ) THEN
        RAISE EXCEPTION 'Performance users already exist';
    END IF;
END
$$;

-- Used only to generate BCrypt hashes for local test users.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;

-- Generate the password hash once, then reuse it for test users.
INSERT INTO users (username, password)
SELECT 'perf_user_' || n, password_hash
FROM generate_series(1, 20) AS n
CROSS JOIN (
    SELECT crypt(
        'local-load-test-password',
        gen_salt('bf', 10)
    ) AS password_hash
) AS password;

INSERT INTO role (name)
SELECT 'ROLE_USER'
WHERE NOT EXISTS (
    SELECT 1 FROM role WHERE name = 'ROLE_USER'
);

INSERT INTO users_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN role r
WHERE u.username IN (
    SELECT 'perf_user_' || n
    FROM generate_series(21, 100) AS n
)
  AND r.name = 'ROLE_USER';

-- Two personal categories are sufficient for this data-size scenario.
INSERT INTO category (name, type, user_id)
SELECT category.name, category.type, u.id
FROM users u
CROSS JOIN (
    VALUES
        ('Groceries', 'EXPENSE'),
        ('Salary', 'INCOME')
) AS category(name, type)
WHERE u.username IN (
    SELECT 'perf_user_' || n
    FROM generate_series(21, 100) AS n
);

INSERT INTO account (name, amount, description, version, user_id)
SELECT
    'Performance account',
    0,
    'Synthetic local test data',
    0,
    id
FROM users
WHERE username LIKE 'perf_user_%';

INSERT INTO entry (
    title,
    type,
    amount,
    date_time,
    description,
    category_id,
    account_id,
    user_id
)
SELECT
    'Operation ' || n,
    CASE WHEN n % 10 = 0 THEN 'INCOME' ELSE 'EXPENSE' END,
    CASE
        WHEN n % 10 = 0 THEN 500.00
        ELSE (5 + n % 95)::numeric + 0.50
    END,
    TIMESTAMP '2021-01-01'
        + (n - 1) * INTERVAL '144 minutes',
    'Synthetic transaction for database size and API testing.',
    c.category_id,
    a.account_id,
    u.id
FROM users u
JOIN account a ON a.user_id = u.id
CROSS JOIN generate_series(1, 18250) AS n
JOIN category c
    ON c.user_id = u.id
   AND c.type = CASE
       WHEN n % 10 = 0 THEN 'INCOME'
       ELSE 'EXPENSE'
   END
WHERWHERE u.username IN (
    SELECT 'perf_user_' || n
    FROM generate_series(21, 100) AS n
)E u.username LIKE 'perf_user_%';

-- SQL inserts bypass EntryService, so calculate matching balances.
UPDATE account a
SET amount = totals.balance
FROM (
    SELECT
        account_id,
        SUM(
            CASE WHEN type = 'INCOME' THEN amount ELSE -amount END
        ) AS balance
    FROM entry
    GROUP BY account_id
) totals
WHERE a.account_id = totals.account_id
  AND a.user_id IN (
      SELECT id FROM users WHERE username LIKE 'perf_user_%'
  );

COMMIT;

ANALYZE;