package fi.haagahelia.accounting.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateQuote;

@Repository
public class HistoricalExchangeRateRepository {

    private final JdbcTemplate jdbcTemplate;

    public HistoricalExchangeRateRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    public List<HistoricalExchangeRateQuote> findRates(
            LocalDate requestedDate,
            List<String> currencyCodes) {

        if (currencyCodes.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(
            ",",
            Collections.nCopies(currencyCodes.size(), "?")
        );

        String sql = """
            SELECT currency_code, rate, rate_date, fetched_at
            FROM historical_exchange_rate
            WHERE requested_date = ?
              AND currency_code IN (%s)
            ORDER BY currency_code
            """.formatted(placeholders);

        List<Object> parameters = new ArrayList<>();
        parameters.add(Date.valueOf(requestedDate));
        parameters.addAll(currencyCodes);

        return jdbcTemplate.query(
            sql,
            (resultSet, rowNumber) -> new HistoricalExchangeRateQuote(
                Objects.requireNonNull(
                    resultSet.getString("currency_code")
                ),
                Objects.requireNonNull(
                    resultSet.getBigDecimal("rate")
                ),
                Objects.requireNonNull(
                    resultSet.getDate("rate_date")
                ).toLocalDate(),
                Objects.requireNonNull(
                    resultSet.getTimestamp("fetched_at")
                ).toInstant()
            ),
            parameters.toArray()
        );
    }

    @Transactional
    public void saveRates(
            LocalDate requestedDate,
            List<ExchangeRateQuote> rates,
            Instant fetchedAt) {

        for (ExchangeRateQuote quote : rates) {
            jdbcTemplate.update(
                """
                INSERT INTO historical_exchange_rate (
                    requested_date,
                    currency_code,
                    rate,
                    rate_date,
                    fetched_at
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (requested_date, currency_code)
                DO UPDATE SET
                    rate = EXCLUDED.rate,
                    rate_date = EXCLUDED.rate_date,
                    fetched_at = EXCLUDED.fetched_at
                WHERE historical_exchange_rate.fetched_at
                    <= EXCLUDED.fetched_at
                """,
                Date.valueOf(requestedDate),
                quote.currencyCode(),
                quote.rate(),
                Date.valueOf(quote.rateDate()),
                Timestamp.from(fetchedAt)
            );
        }
    }
}