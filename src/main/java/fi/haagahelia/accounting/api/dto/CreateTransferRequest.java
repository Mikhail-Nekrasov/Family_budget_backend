package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;

public record CreateTransferRequest(
    Long fromAccountId,
    Long toAccountId,
    BigDecimal amount,
    String description
) {
}