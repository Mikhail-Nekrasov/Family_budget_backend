package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;

public record CurrencyAmount(
    String currencyCode,
    BigDecimal amount
) {
}