package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

import fi.haagahelia.accounting.model.EntryType;

public record CategoryResponse(
    Long id,
    String name,
    EntryType type,
    boolean global,
    UUID syncId,
    Long version
) {
}