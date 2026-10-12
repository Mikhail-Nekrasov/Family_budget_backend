package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
    Long id,
    Long fromAccountId,
    String fromAccountName,
    Long toAccountId,
    String toAccountName,
    BigDecimal amount,
    LocalDateTime dateTime,
    String description
) {
}