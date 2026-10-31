package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public record CreateAccountRequest(
    String name,
    BigDecimal amount,
    String description,
    UUID syncId,
    String currencyCode,
    String iconKey
) {

    public CreateAccountRequest {
        currencyCode = currencyCode == null
            ? "EUR"
            : currencyCode.trim().toUpperCase(Locale.ROOT);

        iconKey = iconKey == null
            ? "cash"
            : iconKey.trim();
    }
}