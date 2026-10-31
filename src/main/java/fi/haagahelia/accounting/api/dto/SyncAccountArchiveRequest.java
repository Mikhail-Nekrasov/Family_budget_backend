package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncAccountArchiveRequest(
    UUID syncId,
    Long expectedVersion,
    Boolean archived
) {
}