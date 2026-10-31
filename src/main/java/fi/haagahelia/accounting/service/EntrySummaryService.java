package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import fi.haagahelia.accounting.api.dto.CurrencyAmount;
import fi.haagahelia.accounting.api.dto.EntrySummaryResponse;
import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.EntrySummaryRepository;
import fi.haagahelia.accounting.repository.EntrySummaryRepository.DailyTotal;

@Service
public class EntrySummaryService {

    private final EntrySummaryRepository repository;
    private final HistoricalExchangeRateService historicalRates;
    private final CurrencyConversionService conversionService;

    public EntrySummaryService(
            EntrySummaryRepository repository,
            HistoricalExchangeRateService historicalRates,
            CurrencyConversionService conversionService) {

        this.repository = repository;
        this.historicalRates = historicalRates;
        this.conversionService = conversionService;
    }

    public EntrySummaryResponse getSummary(
            User currentUser,
            String targetCurrency) {

        String target = conversionService.normalizeCurrencyCode(
            targetCurrency
        );

        Map<LocalDate, List<DailyTotal>> byDate = new TreeMap<>();

        for (DailyTotal row : repository.findDailyTotals(
                currentUser.getId())) {
            byDate.computeIfAbsent(
                row.operationDate(),
                ignored -> new ArrayList<>()
            ).add(row);
        }

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;

        for (var day : byDate.entrySet()) {
            var snapshot = loadRates(
                day.getKey(),
                day.getValue(),
                target
            );

            List<CurrencyAmount> incomeAmounts = day.getValue()
                .stream()
                .filter(row -> row.type() == EntryType.INCOME)
                .map(row -> new CurrencyAmount(
                    row.currencyCode(),
                    row.amount()
                ))
                .toList();

            List<CurrencyAmount> expenseAmounts = day.getValue()
                .stream()
                .filter(row -> row.type() == EntryType.EXPENSE)
                .map(row -> new CurrencyAmount(
                    row.currencyCode(),
                    row.amount()
                ))
                .toList();

            income = income.add(
                conversionService.convertTotalPrecisely(
                    incomeAmounts,
                    target,
                    snapshot
                )
            );

            expense = expense.add(
                conversionService.convertTotalPrecisely(
                    expenseAmounts,
                    target,
                    snapshot
                )
            );
        }

        BigDecimal roundedIncome =
            conversionService.roundAmount(income, target);

        BigDecimal roundedExpense =
            conversionService.roundAmount(expense, target);

        return new EntrySummaryResponse(
            roundedIncome,
            roundedExpense,
            roundedIncome.subtract(roundedExpense),
            target
        );
    }

    private @Nullable ExchangeRateSnapshot loadRates(
            LocalDate date,
            List<DailyTotal> rows,
            String target) {

        var currencies = new TreeSet<String>();

        for (DailyTotal row : rows) {
            if (row.amount().signum() != 0
                    && !target.equals(row.currencyCode())) {
                currencies.add(row.currencyCode());
            }
        }

        if (currencies.isEmpty()) {
            return null;
        }

        currencies.add(target);

        var response = historicalRates.getRates(
            date,
            List.copyOf(currencies)
        );

        var fetchedAt = response.rates().stream()
            .map(quote -> quote.fetchedAt())
            .min((first, second) -> first.compareTo(second))
            .orElseThrow();

        List<ExchangeRateQuote> quotes = response.rates().stream()
            .map(quote -> new ExchangeRateQuote(
                quote.currencyCode(),
                quote.rate(),
                quote.rateDate()
            ))
            .toList();

        return new ExchangeRateSnapshot(
            response.baseCurrency(),
            response.provider(),
            fetchedAt,
            quotes
        );
    }
}