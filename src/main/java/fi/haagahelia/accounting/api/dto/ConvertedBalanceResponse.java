package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

public record ConvertedBalanceResponse(
    String currencyCode,
    BigDecimal amount,
    @Nullable Instant ratesFetchedAt
) {
}