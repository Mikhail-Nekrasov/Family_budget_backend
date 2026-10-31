package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncUpdateAccountRequest(
    UUID syncId,
    Long expectedVersion,
    String name,
    String description,
    String iconKey
) {
}