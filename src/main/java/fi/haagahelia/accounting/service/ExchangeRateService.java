package fi.haagahelia.accounting.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.client.FrankfurterClient;
import fi.haagahelia.accounting.repository.ExchangeRateRepository;

@Service
public class ExchangeRateService {

    private static final Logger logger =
        LoggerFactory.getLogger(ExchangeRateService.class);

    private final FrankfurterClient client;
    private final ExchangeRateRepository repository;
    private final Duration refreshInterval;
    private final Duration retryInterval;

    private Instant nextAttemptAt = Instant.EPOCH;

    public ExchangeRateService(
            FrankfurterClient client,
            ExchangeRateRepository repository,
            @Value("${app.exchange-rates.refresh-interval:PT6H}")
            Duration refreshInterval,
            @Value("${app.exchange-rates.retry-interval:PT5M}")
            Duration retryInterval) {

        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException(
                "Refresh interval must be positive"
            );
        }

        if (retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException(
                "Retry interval must be positive"
            );
        }

        this.client = client;
        this.repository = repository;
        this.refreshInterval = refreshInterval;
        this.retryInterval = retryInterval;
    }

    public synchronized ExchangeRateSnapshot getLatestRates() {
        var cached = repository.findSnapshot();
        Instant now = Instant.now();

        if (cached.isPresent()
                && cached.get().fetchedAt()
                    .plus(refreshInterval)
                    .isAfter(now)) {
            return cached.get();
        }

        if (now.isBefore(nextAttemptAt)) {
            return cached.orElseThrow(
                ExchangeRateService::unavailable
            );
        }

        nextAttemptAt = now.plus(retryInterval);

        try {
            var rates = client.fetchLatestRates();

            if (rates.isEmpty()) {
                throw new IllegalStateException(
                    "Exchange rate list is empty"
                );
            }

            var snapshot = new ExchangeRateSnapshot(
                "EUR",
                "Frankfurter",
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                rates
            );

            repository.saveSnapshot(snapshot);

            nextAttemptAt = Instant.EPOCH;

            return snapshot;
        } catch (RuntimeException exception) {
            logger.warn(
                "Unable to refresh exchange rates: {}",
                exception.getMessage()
            );

            return cached.orElseThrow(
                ExchangeRateService::unavailable
            );
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Exchange rates are temporarily unavailable"
        );
    }
}