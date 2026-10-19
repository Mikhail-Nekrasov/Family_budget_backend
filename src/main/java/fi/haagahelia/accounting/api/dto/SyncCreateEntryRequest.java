package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import fi.haagahelia.accounting.model.EntryType;

public record SyncCreateEntryRequest(
    UUID syncId,
    String title,
    EntryType type,
    BigDecimal amount,
    LocalDateTime dateTime,
    String description,
    UUID accountSyncId,
    UUID categorySyncId
) {
}