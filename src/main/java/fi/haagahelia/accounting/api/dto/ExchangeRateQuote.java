package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExchangeRateQuote(
    String currencyCode,
    BigDecimal rate,
    LocalDate rateDate
) {
}