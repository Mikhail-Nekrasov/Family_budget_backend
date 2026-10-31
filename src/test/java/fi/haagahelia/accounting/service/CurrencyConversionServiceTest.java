package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CurrencyAmount;
import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;

class CurrencyConversionServiceTest {

    private final CurrencyConversionService service =
        new CurrencyConversionService();

    private ExchangeRateSnapshot snapshot() {
        return new ExchangeRateSnapshot(
            "EUR",
            "Frankfurter",
            Instant.parse("2026-10-31T12:00:00Z"),
            List.of(
                new ExchangeRateQuote(
                    "EUR",
                    BigDecimal.ONE,
                    LocalDate.of(2026, 10, 30)
                ),
                new ExchangeRateQuote(
                    "USD",
                    new BigDecimal("1.10"),
                    LocalDate.of(2026, 10, 30)
                ),
                new ExchangeRateQuote(
                    "RUB",
                    new BigDecimal("100"),
                    LocalDate.of(2026, 10, 30)
                )
            )
        );
    }

    @Test
    void convertsRubToUsdThroughCommonBase() {
        var result = service.convertTotal(
            List.of(
                new CurrencyAmount(
                    "RUB",
                    new BigDecimal("10000")
                )
            ),
            "USD",
            snapshot()
        );

        assertEquals(new BigDecimal("110.00"), result);
    }

    @Test
    void sumsDifferentCurrenciesIntoOneTotal() {
        var result = service.convertTotal(
            List.of(
                new CurrencyAmount(
                    "EUR",
                    new BigDecimal("100")
                ),
                new CurrencyAmount(
                    "USD",
                    new BigDecimal("11")
                ),
                new CurrencyAmount(
                    "RUB",
                    new BigDecimal("1000")
                )
            ),
            "EUR",
            snapshot()
        );

        assertEquals(new BigDecimal("120.00"), result);
    }

    @Test
    void supportsNegativeBalances() {
        var result = service.convertTotal(
            List.of(
                new CurrencyAmount(
                    "RUB",
                    new BigDecimal("-1000")
                )
            ),
            "USD",
            snapshot()
        );

        assertEquals(new BigDecimal("-11.00"), result);
    }

    @Test
    void sameCurrencyDoesNotRequireRates() {
        var result = service.convertTotal(
            List.of(
                new CurrencyAmount(
                    "USD",
                    new BigDecimal("25.50")
                )
            ),
            " usd ",
            null
        );

        assertEquals(new BigDecimal("25.50"), result);
    }

    @Test
    void roundsAfterSummingConvertedAmounts() {
        var rates = new ExchangeRateSnapshot(
            "EUR",
            "Frankfurter",
            Instant.parse("2026-10-31T12:00:00Z"),
            List.of(
                new ExchangeRateQuote(
                    "USD",
                    new BigDecimal("3"),
                    LocalDate.of(2026, 10, 30)
                )
            )
        );

        var amounts = List.of(
            new CurrencyAmount("USD", new BigDecimal("0.01")),
            new CurrencyAmount("USD", new BigDecimal("0.01")),
            new CurrencyAmount("USD", new BigDecimal("0.01"))
        );

        assertEquals(
            new BigDecimal("0.01"),
            service.convertTotal(amounts, "EUR", rates)
        );
    }

    @Test
    void missingRateDoesNotSilentlyExcludeAccount() {
        var exception = assertThrows(
            ResponseStatusException.class,
            () -> service.convertTotal(
                List.of(
                    new CurrencyAmount(
                        "CHF",
                        new BigDecimal("100")
                    )
                ),
                "EUR",
                snapshot()
            )
        );

        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.getStatusCode()
        );
    }

    @Test
    void rejectsUnknownTargetCurrency() {
        var exception = assertThrows(
            ResponseStatusException.class,
            () -> service.convertTotal(
                List.of(),
                "ZZZ",
                null
            )
        );

        assertEquals(
            HttpStatus.BAD_REQUEST,
            exception.getStatusCode()
        );
    }
}