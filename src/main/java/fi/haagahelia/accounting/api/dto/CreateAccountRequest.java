package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateAccountRequest(
    String name,
    BigDecimal amount,
    String description,
    UUID syncId
) {
}