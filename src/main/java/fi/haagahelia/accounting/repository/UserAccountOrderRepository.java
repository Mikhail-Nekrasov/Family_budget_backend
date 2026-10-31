package fi.haagahelia.accounting.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserAccountOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserAccountOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findVersion(Long userId) {
        return jdbcTemplate.query(
            """
            SELECT version
            FROM user_account_order
            WHERE user_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getLong("version"),
            userId
        ).stream().findFirst();
    }

    public List<Long> findAccountIds(Long userId) {
        return jdbcTemplate.query(
            """
            SELECT account_id
            FROM user_account_order_item
            WHERE user_id = ?
            ORDER BY sort_order, account_id
            """,
            (resultSet, rowNumber) -> resultSet.getLong("account_id"),
            userId
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createIfMissing(Long userId) {
        jdbcTemplate.update(
            """
            INSERT INTO user_account_order (user_id, version)
            VALUES (?, 0)
            ON CONFLICT (user_id) DO NOTHING
            """,
            userId
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean replaceOrder(
            Long userId,
            long expectedVersion,
            List<Long> accountIds) {

        int updated = jdbcTemplate.update(
            """
            UPDATE user_account_order
            SET version = version + 1
            WHERE user_id = ?
              AND version = ?
            """,
            userId,
            expectedVersion
        );

        if (updated == 0) {
            return false;
        }

        jdbcTemplate.update(
            """
            DELETE FROM user_account_order_item
            WHERE user_id = ?
            """,
            userId
        );

        for (int position = 0; position < accountIds.size(); position++) {
            jdbcTemplate.update(
                """
                INSERT INTO user_account_order_item (
                    user_id,
                    account_id,
                    sort_order
                )
                VALUES (?, ?, ?)
                """,
                userId,
                accountIds.get(position),
                position
            );
        }

        return true;
    }

    public record OrderSnapshot(
        List<Long> accountIds,
        long version
    ) {
    }

    public Optional<OrderSnapshot> findSnapshot(Long userId) {
        var result = jdbcTemplate.query(
            """
            SELECT orders.version, items.account_id
            FROM user_account_order orders
            LEFT JOIN user_account_order_item items
                ON items.user_id = orders.user_id
            WHERE orders.user_id = ?
            ORDER BY items.sort_order, items.account_id
            """,
            resultSet -> {
                if (!resultSet.next()) {
                    return Optional.<OrderSnapshot>empty();
                }

                long version = resultSet.getLong("version");
                List<Long> accountIds = new ArrayList<>();

                do {
                    Long accountId = resultSet.getObject(
                        "account_id", Long.class
                    );

                    if (accountId != null) {
                        accountIds.add(accountId);
                    }
                } while (resultSet.next());

                return Optional.of(
                    new OrderSnapshot(List.copyOf(accountIds), version)
                );
            },
            userId
        );

        return Objects.requireNonNull(result);
    }
}