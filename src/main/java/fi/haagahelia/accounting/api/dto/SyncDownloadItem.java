package fi.haagahelia.accounting.api.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record SyncDownloadItem(
    long position, String entityType, UUID syncId, String action, JsonNode data
) {
}
