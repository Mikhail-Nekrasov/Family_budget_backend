package fi.haagahelia.accounting.api.dto;

import java.util.List;
import java.util.UUID;

public record SyncDownloadPage(
    UUID downloadId, String mode, List<SyncDownloadItem> items,
    Long nextAfter, UUID nextCursor
) {
}
