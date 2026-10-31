package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record HistoricalExchangeRateQuote(
    String currencyCode,
    BigDecimal rate,
    LocalDate rateDate,
    Instant fetchedAt
) {
}