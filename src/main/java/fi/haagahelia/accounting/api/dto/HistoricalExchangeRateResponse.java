package fi.haagahelia.accounting.api.dto;

import java.time.LocalDate;
import java.util.List;

public record HistoricalExchangeRateResponse(
    LocalDate requestedDate,
    String baseCurrency,
    String provider,
    List<HistoricalExchangeRateQuote> rates
) {
    public HistoricalExchangeRateResponse {
        rates = List.copyOf(rates);
    }
}