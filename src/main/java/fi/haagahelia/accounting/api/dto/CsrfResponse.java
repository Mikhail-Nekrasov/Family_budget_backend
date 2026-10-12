package fi.haagahelia.accounting.api.dto;

public record CsrfResponse(
    String token,
    String headerName
) {
}