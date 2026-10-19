package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncDeleteEntryRequest(
    UUID syncId,
    Long expectedVersion
) {
}