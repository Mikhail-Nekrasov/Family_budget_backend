package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;

public record EntrySummaryResponse(
    BigDecimal totalIncome,
    BigDecimal totalExpense,
    BigDecimal netAmount,
    String currencyCode
) {
}