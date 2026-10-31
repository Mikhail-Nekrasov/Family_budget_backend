package fi.haagahelia.accounting.api.dto;

import java.time.Instant;
import java.util.List;

public record ExchangeRateSnapshot(
    String baseCurrency,
    String provider,
    Instant fetchedAt,
    List<ExchangeRateQuote> rates
) {
    public ExchangeRateSnapshot {
        rates = List.copyOf(rates);
    }
}