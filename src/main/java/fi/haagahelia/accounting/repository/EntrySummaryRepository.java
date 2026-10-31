package fi.haagahelia.accounting.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import fi.haagahelia.accounting.model.EntryType;

@Repository
public class EntrySummaryRepository {

    public record DailyTotal(
        LocalDate operationDate,
        String currencyCode,
        EntryType type,
        BigDecimal amount
    ) {
    }

    private final JdbcTemplate jdbcTemplate;

    public EntrySummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DailyTotal> findDailyTotals(Long userId) {
        return jdbcTemplate.query(
            """
            SELECT CAST(e.date_time AS DATE) AS operation_date,
                   a.currency_code,
                   e.type AS entry_type,
                   SUM(e.amount) AS total_amount
            FROM entry e
            JOIN account a ON a.account_id = e.account_id
            WHERE e.user_id = ?
            GROUP BY CAST(e.date_time AS DATE),
                     a.currency_code,
                     e.type
            ORDER BY operation_date, a.currency_code, e.type
            """,
            (resultSet, rowNumber) -> new DailyTotal(
                Objects.requireNonNull(
                    resultSet.getDate("operation_date")
                ).toLocalDate(),
                Objects.requireNonNull(
                    resultSet.getString("currency_code")
                ),
                EntryType.valueOf(
                    Objects.requireNonNull(
                        resultSet.getString("entry_type")
                    )
                ),
                Objects.requireNonNull(
                    resultSet.getBigDecimal("total_amount")
                )
            ),
            userId
        );
    }
}