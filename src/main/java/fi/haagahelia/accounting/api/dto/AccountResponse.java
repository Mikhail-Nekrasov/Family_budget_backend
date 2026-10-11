package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;

public record AccountResponse(
    Long id,
    String name,
    BigDecimal amount,
    String description
) {
}