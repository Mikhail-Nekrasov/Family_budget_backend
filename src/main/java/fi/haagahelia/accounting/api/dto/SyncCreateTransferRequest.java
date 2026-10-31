package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SyncCreateTransferRequest(
    UUID syncId,
    UUID fromAccountSyncId,
    UUID toAccountSyncId,
    BigDecimal amount,
    LocalDateTime dateTime,
    String description,
    BigDecimal targetAmount
) {
}