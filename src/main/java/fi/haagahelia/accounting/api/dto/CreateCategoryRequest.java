package fi.haagahelia.accounting.api.dto;

import java.util.UUID;

import fi.haagahelia.accounting.model.EntryType;

public record CreateCategoryRequest(
    String name,
    EntryType type,
    UUID syncId
) {
}