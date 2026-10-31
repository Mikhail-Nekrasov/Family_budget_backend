package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransferResponse(
    Long id,
    Long fromAccountId,
    String fromAccountName,
    Long toAccountId,
    String toAccountName,
    BigDecimal amount,
    LocalDateTime dateTime,
    String description,
    UUID syncId,
    BigDecimal targetAmount,
    String sourceCurrencyCode,
    String targetCurrencyCode
) {
}