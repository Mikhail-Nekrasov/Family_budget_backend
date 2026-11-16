package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncUpdateCategoryRequest(
    UUID syncId,
    Long expectedVersion,
    String name
) {
}
