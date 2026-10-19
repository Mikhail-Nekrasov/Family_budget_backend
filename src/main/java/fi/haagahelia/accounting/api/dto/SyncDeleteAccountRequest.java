package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncDeleteAccountRequest(
    UUID syncId,
    Long expectedVersion
) {
}