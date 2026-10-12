package fi.haagahelia.accounting.api.dto;

public record RegisterRequest(
    String username,
    String password,
    String passwordConfirm
) {
}