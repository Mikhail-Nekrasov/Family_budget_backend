package fi.haagahelia.accounting.api.dto;

import java.util.List;
import java.util.UUID;

public record UpdateAccountOrderRequest(
    List<UUID> accountSyncIds,
    Long expectedVersion
) {
}