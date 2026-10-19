package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncDeleteCategoryRequest(UUID syncId, Long expectedVersion) {
}
