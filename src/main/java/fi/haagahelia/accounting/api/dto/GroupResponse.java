package fi.haagahelia.accounting.api.dto;

import java.time.LocalDateTime;

public record GroupResponse(
    Long id,
    String name,
    Long ownerId,
    LocalDateTime createdAt
) {
}