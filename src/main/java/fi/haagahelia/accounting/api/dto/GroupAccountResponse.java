package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import fi.haagahelia.accounting.model.AccountPermission;

public record GroupAccountResponse(
    Long groupId,
    Long accountId,
    String name,
    BigDecimal amount,
    Long ownerId,
    AccountPermission permission,
    String currencyCode,
    String iconKey,
    Instant archivedAt
) {
}