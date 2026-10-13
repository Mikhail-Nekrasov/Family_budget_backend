package fi.haagahelia.accounting.api.dto;

import fi.haagahelia.accounting.model.AccountPermission;

public record SetGroupAccountAccessRequest(
    AccountPermission permission
) {
}