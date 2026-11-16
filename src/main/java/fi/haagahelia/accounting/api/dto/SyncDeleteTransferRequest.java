package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

public record SyncDeleteTransferRequest(UUID syncId, Long expectedVersion) {
}
