package fi.haagahelia.accounting.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SyncDownloadResponse(
    UUID downloadId, String mode, long itemCount, int pageSize,
    Instant pagesExpireAt, Instant cursorExpiresAt
) {
}
