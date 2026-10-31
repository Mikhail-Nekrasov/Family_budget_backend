package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(
    Long fromAccountId,
    Long toAccountId,
    BigDecimal amount,
    String description,
    UUID syncId,
    BigDecimal targetAmount
) {
}