package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.client.FrankfurterClient;
import fi.haagahelia.accounting.repository.ExchangeRateRepository;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private FrankfurterClient client;

    @Mock
    private ExchangeRateRepository repository;

    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        service = new ExchangeRateService(
            client,
            repository,
            Duration.ofHours(6),
            Duration.ofMinutes(5)
        );
    }

    private List<ExchangeRateQuote> quotes() {
        return List.of(
            new ExchangeRateQuote(
                "EUR",
                BigDecimal.ONE,
                LocalDate.of(2026, 10, 30)
            ),
            new ExchangeRateQuote(
                "RUB",
                new BigDecimal("90.14"),
                LocalDate.of(2026, 10, 30)
            )
        );
    }

    private ExchangeRateSnapshot snapshot(Instant fetchedAt) {
        return new ExchangeRateSnapshot(
            "EUR",
            "Frankfurter",
            fetchedAt,
            quotes()
        );
    }

    @Test
    void returnsFreshCacheWithoutCallingProvider() {
        var cached = snapshot(Instant.now());

        when(repository.findSnapshot())
            .thenReturn(Optional.of(cached));

        assertEquals(cached, service.getLatestRates());

        verifyNoInteractions(client);
        verify(repository, never()).saveSnapshot(any());
    }

    @Test
    void downloadsAndSavesFirstSnapshot() {
        when(repository.findSnapshot())
            .thenReturn(Optional.empty());

        when(client.fetchLatestRates()).thenReturn(quotes());

        var result = service.getLatestRates();

        assertEquals("EUR", result.baseCurrency());
        assertEquals("Frankfurter", result.provider());
        assertEquals(quotes(), result.rates());

        verify(repository).saveSnapshot(result);
    }

    @Test
    void refreshesExpiredSnapshot() {
        var expired = snapshot(Instant.EPOCH);

        when(repository.findSnapshot())
            .thenReturn(Optional.of(expired));

        when(client.fetchLatestRates()).thenReturn(quotes());

        var result = service.getLatestRates();

        assertTrue(result.fetchedAt().isAfter(expired.fetchedAt()));
        verify(repository).saveSnapshot(result);
    }

    @Test
    void providerFailureReturnsOldCacheAndDelaysRetry() {
        var expired = snapshot(Instant.EPOCH);

        when(repository.findSnapshot())
            .thenReturn(Optional.of(expired));

        when(client.fetchLatestRates())
            .thenThrow(new IllegalStateException("Provider unavailable"));

        assertEquals(expired, service.getLatestRates());
        assertEquals(expired, service.getLatestRates());

        verify(client, times(1)).fetchLatestRates();
        verify(repository, never()).saveSnapshot(any());
    }

    @Test
    void missingCacheAndProviderFailureReturnServiceUnavailable() {
        when(repository.findSnapshot())
            .thenReturn(Optional.empty());

        when(client.fetchLatestRates())
            .thenThrow(new IllegalStateException("Provider unavailable"));

        var exception = assertThrows(
            ResponseStatusException.class,
            () -> service.getLatestRates()
        );

        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.getStatusCode()
        );

        assertThrows(
            ResponseStatusException.class,
            () -> service.getLatestRates()
        );

        verify(client, times(1)).fetchLatestRates();
        verify(repository, never()).saveSnapshot(any());
    }
}