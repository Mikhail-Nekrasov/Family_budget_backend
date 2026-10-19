package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

/** Null cursor requests a complete authoritative snapshot. */
public record SyncDownloadRequest(UUID cursor) {
}
