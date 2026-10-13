package fi.haagahelia.accounting.api.dto;

import java.time.LocalDateTime;

public record GroupMemberResponse(
    Long userId,
    String username,
    boolean owner,
    LocalDateTime joinedAt
) {
}