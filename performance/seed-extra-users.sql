\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() <> 'family_budget_load' THEN
        RAISE EXCEPTION 'This script requires family_budget_load';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM generate_series(1, 20) AS n
        WHERE NOT EXISTS (
            SELECT 1
            FROM users
            WHERE username = 'perf_user_' || n
        )
    ) THEN
        RAISE EXCEPTION 'Expected existing performance users 1–20';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM users
        WHERE username IN (
            SELECT 'perf_user_' || n
            FROM generate_series(21, 100) AS n
        )
    ) THEN
        RAISE EXCEPTION 'Some performance users 21–100 already exist';
    END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;

-- Keep the new user IDs separate from existing users.
CREATE TEMP TABLE new_performance_users (
    id BIGINT PRIMARY KEY
) ON COMMIT DROP;

WITH inserted AS (
    INSERT INTO users (username, password)
    SELECT 'perf_user_' || n, password_hash
    FROM generate_series(21, 100) AS n
    CROSS JOIN (
        SELECT crypt(
            'local-load-test-password',
            gen_salt('bf', 10)
        ) AS password_hash
    ) AS password
    RETURNING id
)
INSERT INTO new_performance_users (id)
SELECT id FROM inserted;

INSERT INTO role (name)
SELECT 'ROLE_USER'
WHERE NOT EXISTS (
    SELECT 1 FROM role WHERE name = 'ROLE_USER'
);

INSERT INTO users_roles (user_id, role_id)
SELECT u.id, r.id
FROM new_performance_users u
CROSS JOIN role r
WHERE r.name = 'ROLE_USER';

INSERT INTO category (name, type, user_id)
SELECT category.name, category.type, u.id
FROM new_performance_users u
CROSS JOIN (
    VALUES
        ('Groceries', 'EXPENSE'),
        ('Salary', 'INCOME')
) AS category(name, type);

INSERT INTO account (name, amount, description, version, user_id)
SELECT
    'Performance account',
    0,
    'Synthetic local test data',
    0,
    id
FROM new_performance_users;

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
FROM new_performance_users u
JOIN account a ON a.user_id = u.id
CROSS JOIN generate_series(1, 18250) AS n
JOIN category c
    ON c.user_id = u.id
   AND c.type = CASE
       WHEN n % 10 = 0 THEN 'INCOME'
       ELSE 'EXPENSE'
   END;

UPDATE account a
SET amount = totals.balance
FROM (
    SELECT
        e.account_id,
        SUM(
            CASE WHEN e.type = 'INCOME'
                THEN e.amount
                ELSE -e.amount
            END
        ) AS balance
    FROM entry e
    JOIN new_performance_users u ON u.id = e.user_id
    GROUP BY e.account_id
) totals
WHERE a.account_id = totals.account_id;

COMMIT;

ANALYZE;