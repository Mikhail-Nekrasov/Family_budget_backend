package fi.haagahelia.accounting.api.dto;

import java.time.LocalDateTime;

public record GroupInvitationResponse(
    Long id,
    Long groupId,
    String groupName,
    String invitedUsername,
    String invitedByUsername,
    LocalDateTime createdAt
) {
}