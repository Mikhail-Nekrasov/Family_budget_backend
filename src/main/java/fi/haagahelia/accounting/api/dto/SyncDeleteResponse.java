package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncDeleteResponse(
    UUID syncId,
    boolean deleted
) {
}