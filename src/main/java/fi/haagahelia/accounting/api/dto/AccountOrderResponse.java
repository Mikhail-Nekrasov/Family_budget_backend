package fi.haagahelia.accounting.api.dto;

import java.util.List;
import java.util.UUID;

public record AccountOrderResponse(
    List<UUID> accountSyncIds,
    long version
) {
}