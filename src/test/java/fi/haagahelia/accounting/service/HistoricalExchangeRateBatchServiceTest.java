package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.client.FrankfurterClient;
import fi.haagahelia.accounting.repository.HistoricalExchangeRateRepository;

@ExtendWith(MockitoExtension.class)
class HistoricalExchangeRateBatchServiceTest {

    private static final LocalDate FIRST = LocalDate.of(2025, 1, 10);
    private static final LocalDate LAST = LocalDate.of(2025, 1, 12);

    @Mock
    private HistoricalExchangeRateRepository repository;

    @Mock
    private FrankfurterClient client;

    private HistoricalExchangeRateService service;

    @BeforeEach
    void setUp() {
        service = new HistoricalExchangeRateService(
            repository, client, new CurrencyConversionService()
        );
    }

    private ExchangeRateQuote quote(LocalDate date) {
        return new ExchangeRateQuote("USD", new BigDecimal("2"), date);
    }

    private Map<LocalDate, List<String>> requests() {
        return Map.of(
            FIRST, List.of("EUR", "USD"),
            LAST, List.of("EUR", "USD")
        );
    }

    @Test
    void loadsManyDatesWithOneRequestAndStoresOnlyNeededDates() {
        when(repository.findRatesForRange(FIRST, LAST, List.of("USD")))
            .thenReturn(Map.of());
        when(client.fetchRatesForRange(FIRST, LAST, List.of("USD")))
            .thenReturn(List.of(quote(FIRST), quote(FIRST.plusDays(1)), quote(LAST)));

        var result = service.getRatesForDates(requests());

        assertEquals(2, result.size());
        assertEquals(2, result.get(FIRST).rates().size());
        verify(client, times(1)).fetchRatesForRange(FIRST, LAST, List.of("USD"));
        verify(repository).saveRateSeries(eq(List.of(quote(FIRST), quote(LAST))), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void incompleteRangeNeverProducesAnIncompleteSummary() {
        when(repository.findRatesForRange(FIRST, LAST, List.of("USD")))
            .thenReturn(Map.of());
        when(client.fetchRatesForRange(FIRST, LAST, List.of("USD")))
            .thenReturn(List.of(quote(FIRST)));

        var exception = assertThrows(ResponseStatusException.class,
            () -> service.getRatesForDates(requests()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        verify(repository).saveRateSeries(eq(List.of(quote(FIRST))), any());
    }
}
