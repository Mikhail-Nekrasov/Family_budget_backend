package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
    Long id,
    String name,
    BigDecimal amount,
    String description,
    UUID syncId,
    Long version,
    String currencyCode,
    String iconKey,
    Instant archivedAt
) {
}