package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateResponse;
import fi.haagahelia.accounting.client.FrankfurterClient;
import fi.haagahelia.accounting.repository.HistoricalExchangeRateRepository;

@Service
public class HistoricalExchangeRateService {

    private static final Logger logger =
        LoggerFactory.getLogger(HistoricalExchangeRateService.class);

    private static final Duration REFRESH_INTERVAL =
        Duration.ofHours(6);

    private final HistoricalExchangeRateRepository repository;
    private final FrankfurterClient client;
    private final CurrencyConversionService conversionService;

    public HistoricalExchangeRateService(
            HistoricalExchangeRateRepository repository,
            FrankfurterClient client,
            CurrencyConversionService conversionService) {

        this.repository = repository;
        this.client = client;
        this.conversionService = conversionService;
    }

    public synchronized HistoricalExchangeRateResponse getRates(
            LocalDate requestedDate,
            List<String> currencyCodes) {

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        if (requestedDate == null || requestedDate.isAfter(today)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Exchange rate date must not be in the future"
            );
        }

        if (currencyCodes == null
                || currencyCodes.isEmpty()
                || currencyCodes.size() > 200) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Request between 1 and 200 currencies"
            );
        }

        List<String> selected = currencyCodes.stream()
            .map(conversionService::normalizeCurrencyCode)
            .distinct()
            .sorted()
            .toList();

        List<String> storedCurrencies = selected.stream()
            .filter(code -> !"EUR".equals(code))
            .toList();

        Map<String, HistoricalExchangeRateQuote> available =
            new HashMap<>();

        for (var quote : repository.findRates(
                requestedDate,
                storedCurrencies)) {
            available.put(quote.currencyCode(), quote);
        }

        Instant now = Instant.now();

        // Recent observations can still be updated by the provider.
        boolean recentDate =
            !requestedDate.isBefore(today.minusDays(7));

        List<String> toFetch = storedCurrencies.stream()
            .filter(code -> {
                var cached = available.get(code);

                return cached == null
                    || (recentDate
                        && !cached.fetchedAt()
                            .plus(REFRESH_INTERVAL)
                            .isAfter(now));
            })
            .toList();

        if (!toFetch.isEmpty()) {
            try {
                List<ExchangeRateQuote> loaded =
                    client.fetchRatesForCurrencies(
                        requestedDate,
                        toFetch
                    );

                validateLoadedCurrencies(loaded, toFetch);

                Instant fetchedAt = Instant.now()
                    .truncatedTo(ChronoUnit.MICROS);

                repository.saveRates(
                    requestedDate,
                    loaded,
                    fetchedAt
                );

                for (var quote : repository.findRates(
                        requestedDate,
                        storedCurrencies)) {
                    available.put(quote.currencyCode(), quote);
                }
            } catch (RuntimeException exception) {
                logger.warn(
                    "Unable to load historical exchange rates for {}: {}",
                    requestedDate,
                    exception.getMessage()
                );
            }
        }

        List<HistoricalExchangeRateQuote> result =
            new ArrayList<>();

        for (String code : selected) {
            if ("EUR".equals(code)) {
                result.add(new HistoricalExchangeRateQuote(
                    "EUR",
                    BigDecimal.ONE,
                    requestedDate,
                    now
                ));
                continue;
            }

            var quote = available.get(code);

            if (quote == null) {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Historical exchange rate is unavailable for "
                        + code + " on " + requestedDate
                );
            }

            result.add(quote);
        }

        return new HistoricalExchangeRateResponse(
            requestedDate,
            "EUR",
            "Frankfurter",
            result
        );
    }

    private void validateLoadedCurrencies(
            List<ExchangeRateQuote> loaded,
            List<String> requested) {

        Set<String> returned = new HashSet<>();

        for (var quote : loaded) {
            if (!returned.add(quote.currencyCode())) {
                throw new IllegalStateException(
                    "Duplicate historical exchange rate"
                );
            }
        }

        if (!returned.equals(new HashSet<>(requested))) {
            throw new IllegalStateException(
                "Historical exchange rate response is incomplete"
            );
        }
    }
}