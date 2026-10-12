package fi.haagahelia.accounting.api.dto;

public record ApiError(
    int status,
    String message
) {
}