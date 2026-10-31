package fi.haagahelia.accounting.repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;

@Repository
public class ExchangeRateRepository {

    private final JdbcTemplate jdbcTemplate;

    public ExchangeRateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ExchangeRateSnapshot> findSnapshot() {
        return Objects.requireNonNull(
            jdbcTemplate.query(
                """
                SELECT state.fetched_at,
                       rate.currency_code,
                       rate.rate,
                       rate.rate_date
                FROM exchange_rate_state state
                JOIN exchange_rate rate ON TRUE
                WHERE state.singleton_id = 1
                  AND state.fetched_at IS NOT NULL
                ORDER BY rate.currency_code
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.<ExchangeRateSnapshot>empty();
                    }

                    var fetchedAt = Objects.requireNonNull(
                        resultSet.getTimestamp("fetched_at")
                    ).toInstant();

                    List<ExchangeRateQuote> rates = new ArrayList<>();

                    do {
                        rates.add(new ExchangeRateQuote(
                            Objects.requireNonNull(
                                resultSet.getString("currency_code")
                            ),
                            Objects.requireNonNull(
                                resultSet.getBigDecimal("rate")
                            ),
                            Objects.requireNonNull(
                                resultSet.getDate("rate_date")
                            ).toLocalDate()
                        ));
                    } while (resultSet.next());

                    return Optional.of(new ExchangeRateSnapshot(
                        "EUR",
                        "Frankfurter",
                        fetchedAt,
                        rates
                    ));
                }
            )
        );
    }

    @Transactional
    public void saveSnapshot(ExchangeRateSnapshot snapshot) {
        if (snapshot.rates().isEmpty()) {
            throw new IllegalArgumentException(
                "Exchange rate snapshot must not be empty"
            );
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE exchange_rate_state
            SET fetched_at = ?
            WHERE singleton_id = 1
            """,
            Timestamp.from(snapshot.fetchedAt())
        );

        if (updated != 1) {
            throw new IllegalStateException(
                "Exchange rate state is missing"
            );
        }

        jdbcTemplate.update("DELETE FROM exchange_rate");

        for (ExchangeRateQuote quote : snapshot.rates()) {
            jdbcTemplate.update(
                """
                INSERT INTO exchange_rate (
                    currency_code,
                    rate,
                    rate_date
                )
                VALUES (?, ?, ?)
                """,
                quote.currencyCode(),
                quote.rate(),
                java.sql.Date.valueOf(quote.rateDate())
            );
        }
    }
}