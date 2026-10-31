package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CurrencyAmount;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;

@Service
public class CurrencyConversionService {

    private static final MathContext CALCULATION_CONTEXT =
        new MathContext(64, RoundingMode.HALF_EVEN);

    public String normalizeCurrencyCode(String value) {
        if (value == null) {
            throw invalidCurrency();
        }

        String code = value.trim().toUpperCase(Locale.ROOT);

        if (!code.matches("[A-Z]{3}")) {
            throw invalidCurrency();
        }

        try {
            if (Currency.getInstance(code).getDefaultFractionDigits() < 0) {
                throw invalidCurrency();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidCurrency();
        }

        return code;
    }

    public BigDecimal convertTotal(
            List<CurrencyAmount> amounts,
            String targetCurrency,
            @Nullable ExchangeRateSnapshot snapshot) {

        return roundAmount(
            convertTotalPrecisely(
                amounts,
                targetCurrency,
                snapshot
            ),
            targetCurrency
        );
    }

    public BigDecimal convertTotalPrecisely(
            List<CurrencyAmount> amounts,
            String targetCurrency,
            @Nullable ExchangeRateSnapshot snapshot) {

        String target = normalizeCurrencyCode(targetCurrency);
        Map<String, BigDecimal> rates = new HashMap<>();

        rates.put("EUR", BigDecimal.ONE);

        if (snapshot != null) {
            if (!"EUR".equals(snapshot.baseCurrency())) {
                throw new IllegalStateException(
                    "Exchange rate base must be EUR"
                );
            }

            for (var quote : snapshot.rates()) {
                rates.put(quote.currencyCode(), quote.rate());
            }
        }

        BigDecimal total = BigDecimal.ZERO;

        for (CurrencyAmount item : amounts) {
            BigDecimal amount = item.amount();

            if (amount.signum() == 0) {
                continue;
            }

            if (target.equals(item.currencyCode())) {
                total = total.add(amount);
                continue;
            }

            BigDecimal sourceRate = requireRate(
                rates,
                item.currencyCode()
            );
            BigDecimal targetRate = requireRate(rates, target);

            BigDecimal converted = amount
                .multiply(targetRate)
                .divide(sourceRate, CALCULATION_CONTEXT);

            total = total.add(converted);
        }

        return total;
    }

    public BigDecimal roundAmount(
            BigDecimal amount,
            String currencyCode) {

        String code = normalizeCurrencyCode(currencyCode);

        int fractionDigits = Currency.getInstance(code)
            .getDefaultFractionDigits();

        return amount.setScale(
            fractionDigits,
            RoundingMode.HALF_UP
        );
    }

    private BigDecimal requireRate(
            Map<String, BigDecimal> rates,
            String currencyCode) {

        BigDecimal rate = rates.get(currencyCode);

        if (rate == null || rate.signum() <= 0) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Exchange rate is unavailable for " + currencyCode
            );
        }

        return rate;
    }

    private static ResponseStatusException invalidCurrency() {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "A valid currency code is required"
        );
    }
}