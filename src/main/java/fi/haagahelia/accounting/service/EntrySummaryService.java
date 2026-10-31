package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.time.ZoneOffset;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CurrencyAmount;
import fi.haagahelia.accounting.api.dto.EntrySummaryResponse;
import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateResponse;
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

        Map<LocalDate, List<String>> requests = new TreeMap<>();
        for (var day : byDate.entrySet()) {
            var currencies = new TreeSet<String>();
            for (DailyTotal row : day.getValue()) {
                if (row.amount().signum() != 0 && !target.equals(row.currencyCode())) {
                    currencies.add(row.currencyCode());
                }
            }
            if (!currencies.isEmpty()) {
                if (day.getKey().isAfter(LocalDate.now(ZoneOffset.UTC))) {
                    throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Historical exchange rates are not available for future entries"
                    );
                }
                currencies.add(target);
                requests.put(day.getKey(), List.copyOf(currencies));
            }
        }
        Map<LocalDate, HistoricalExchangeRateResponse> prepared = requests.isEmpty()
            ? Map.of()
            : historicalRates.getRatesForDates(requests);

        for (var day : byDate.entrySet()) {
            var snapshot = loadRates(
                day.getKey(),
                prepared
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
            Map<LocalDate, HistoricalExchangeRateResponse> prepared) {

        var response = prepared.get(date);
        if (response == null) {
            return null;
        }

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
