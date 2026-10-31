package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long accountId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (username, password)
            VALUES ('schema_test_user', 'unused')
            RETURNING id
            """,
            Long.class
        );

        accountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO account (name, amount, user_id)
            VALUES ('Schema account', 100, ?)
            RETURNING account_id
            """,
            Long.class,
            userId
        );

        categoryId = jdbcTemplate.queryForObject(
            """
            INSERT INTO category (name, type, user_id)
            VALUES ('Schema category', 'EXPENSE', ?)
            RETURNING category_id
            """,
            Long.class,
            userId
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveEntryAmount(int amount) {
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO entry (
                    title, type, amount, date_time,
                    category_id, account_id, user_id
                )
                VALUES ('Invalid expense', 'EXPENSE', ?, CURRENT_TIMESTAMP,
                        ?, ?, ?)
                """,
                amount,
                categoryId,
                accountId,
                userId
            )
        );
    }

    @Test
    void rejectsTransferToSameAccount() {
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO transfer (
                    from_account_id,
                    to_account_id,
                    amount,
                    target_amount,
                    source_currency_code,
                    target_currency_code,
                    date_time
                )
                VALUES (?, ?, 10, 10, 'EUR', 'EUR', CURRENT_TIMESTAMP)
                """,
                accountId,
                accountId
            )
        );
    }

    @Test
    void preventsDeletingCategoryUsedByEntry() {
        jdbcTemplate.update(
            """
            INSERT INTO entry (
                title, type, amount, date_time,
                category_id, account_id, user_id
            )
            VALUES ('Valid expense', 'EXPENSE', 10, CURRENT_TIMESTAMP,
                    ?, ?, ?)
            """,
            categoryId,
            accountId,
            userId
        );

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                "DELETE FROM category WHERE category_id = ?",
                categoryId
            )
        );
    }

    @Test
    void preventsDuplicateGroupMembership() {
        Long groupId = jdbcTemplate.queryForObject(
            """
            INSERT INTO budget_group (name, owner_id, created_at)
            VALUES ('Test group', ?, CURRENT_TIMESTAMP)
            RETURNING id
            """,
            Long.class,
            userId
        );

        jdbcTemplate.update(
            """
            INSERT INTO group_membership (group_id, user_id, joined_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """,
            groupId,
            userId
        );

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO group_membership (group_id, user_id, joined_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """,
                groupId,
                userId
            )
        );
    }

    @Test
    void preventsDuplicateAccountAccessForSameGroup() {
        Long groupId = jdbcTemplate.queryForObject(
            """
            INSERT INTO budget_group (name, owner_id, created_at)
            VALUES ('Account access group', ?, CURRENT_TIMESTAMP)
            RETURNING id
            """,
            Long.class,
            userId
        );

        jdbcTemplate.update(
            """
            INSERT INTO group_account_access (
                group_id, account_id, permission, created_at
            )
            VALUES (?, ?, 'READ', CURRENT_TIMESTAMP)
            """,
            groupId,
            accountId
        );

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO group_account_access (
                    group_id, account_id, permission, created_at
                )
                VALUES (?, ?, 'EDIT', CURRENT_TIMESTAMP)
                """,
                groupId,
                accountId
            )
        );
    }

    @Test
    void rejectsNullAccountSyncId() {
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                "UPDATE account SET sync_id = NULL WHERE account_id = ?",
                accountId
            )
        );
    }

    @Test
    void rejectsDuplicateAccountSyncId() {
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO account (
                    name, amount, user_id, sync_id
                )
                SELECT 'Duplicate UUID account', 0, user_id, sync_id
                FROM account
                WHERE account_id = ?
                """,
                accountId
            )
        );
    }
}