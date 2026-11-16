package fi.haagahelia.accounting.service;

import java.util.Currency;

import fi.haagahelia.accounting.model.Account;

final class AmountPrecision {

    private AmountPrecision() {
    }

    static int fractionDigits(Account account) {
        return Currency.getInstance(account.getCurrencyCode())
            .getDefaultFractionDigits();
    }

    static String validationMessage(int fractionDigits) {
        return switch (fractionDigits) {
            case 0 -> "Amount must be a whole number for this currency";
            case 1 -> "Amount must have at most one decimal place";
            default -> "Amount must have at most two decimal places";
        };
    }
}
