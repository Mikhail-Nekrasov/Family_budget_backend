package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import fi.haagahelia.accounting.model.EntryType;

public record UpdateEntryRequest(
    String title,
    EntryType type,
    BigDecimal amount,
    LocalDateTime dateTime,
    String description,
    Long accountId,
    Long categoryId
) {
}