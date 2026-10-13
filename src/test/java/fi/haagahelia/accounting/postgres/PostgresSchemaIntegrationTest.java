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
                    from_account_id, to_account_id, amount, date_time
                )
                VALUES (?, ?, 10, CURRENT_TIMESTAMP)
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
}