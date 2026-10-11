package fi.haagahelia.accounting.api.dto;

import fi.haagahelia.accounting.model.EntryType;

public record CreateCategoryRequest(
    String name,
    EntryType type
) {
}