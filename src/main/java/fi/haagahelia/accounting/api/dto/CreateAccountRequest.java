package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;

public record CreateAccountRequest(
    String name,
    BigDecimal amount,
    String description
) {
}