package fi.haagahelia.accounting.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import fi.haagahelia.accounting.model.EntryType;

public record EntryVersionConflict(
    int status,
    String code,
    String message,
    ServerEntry serverEntry
) {
    public record ServerEntry(
        UUID syncId,
        long id,
        long version,
        String title,
        EntryType type,
        String amount,
        String currencyCode,
        LocalDateTime dateTime,
        String description,
        UUID accountSyncId,
        String accountName,
        UUID categorySyncId,
        String categoryName,
        Editor lastEditedBy
    ) {}

    public record Editor(Long id, String username) {}
}
