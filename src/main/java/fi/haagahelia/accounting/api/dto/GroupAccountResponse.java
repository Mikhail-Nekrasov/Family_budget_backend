package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;

import fi.haagahelia.accounting.model.AccountPermission;

public record GroupAccountResponse(
    Long groupId,
    Long accountId,
    String name,
    BigDecimal amount,
    Long ownerId,
    AccountPermission permission
) {
}