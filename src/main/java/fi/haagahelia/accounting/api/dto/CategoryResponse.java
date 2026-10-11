package fi.haagahelia.accounting.api.dto;

import fi.haagahelia.accounting.model.EntryType;

public record CategoryResponse(
    Long id,
    String name,
    EntryType type,
    boolean global
) {
}