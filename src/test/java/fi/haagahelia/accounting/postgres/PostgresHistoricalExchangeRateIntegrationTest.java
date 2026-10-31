package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.client.FrankfurterClient;
import fi.haagahelia.accounting.repository.HistoricalExchangeRateRepository;
import fi.haagahelia.accounting.service.HistoricalExchangeRateService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresHistoricalExchangeRateIntegrationTest {

    private static final LocalDate DATE =
        LocalDate.of(2025, 1, 18);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HistoricalExchangeRateRepository repository;

    @Autowired
    private HistoricalExchangeRateService service;

    @MockitoBean
    private FrankfurterClient client;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
            "DELETE FROM historical_exchange_rate"
        );
    }

    private ExchangeRateQuote quote(
            String currency,
            String rate) {

        return new ExchangeRateQuote(
            currency,
            new BigDecimal(rate),
            DATE
        );
    }

    @Test
    void storesOnlyRequestedCurrenciesAndReusesThem() throws Exception {
        when(client.fetchRatesForCurrencies(
            DATE,
            List.of("RUB", "USD")
        )).thenReturn(List.of(
            quote("RUB", "105.56"),
            quote("USD", "1.0299")
        ));

        mockMvc.perform(
                get("/api/exchange-rates/history")
                    .param("date", DATE.toString())
                    .param("currencies", "EUR,USD,RUB")
                    .with(user("history_test"))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestedDate")
                .value(DATE.toString()))
            .andExpect(jsonPath("$.baseCurrency").value("EUR"))
            .andExpect(jsonPath("$.rates.length()").value(3))
            .andExpect(jsonPath(
                "$.rates[?(@.currencyCode == 'RUB')].rate",
                org.hamcrest.Matchers.hasItem(105.56)
            ));

        assertEquals(
            Long.valueOf(2),
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM historical_exchange_rate",
                Long.class
            )
        );

        assertEquals(
            Long.valueOf(0),
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM historical_exchange_rate
                WHERE currency_code = 'EUR'
                """,
                Long.class
            )
        );

        mockMvc.perform(
                get("/api/exchange-rates/history")
                    .param("date", DATE.toString())
                    .param("currencies", "USD,RUB")
                    .with(user("history_test"))
            )
            .andExpect(status().isOk());

        verify(client, times(1)).fetchRatesForCurrencies(
            DATE,
            List.of("RUB", "USD")
        );
    }

    @Test
    void downloadsOnlyMissingCurrency() throws Exception {
        repository.saveRates(
            DATE,
            List.of(quote("USD", "1.0299")),
            Instant.now()
        );

        when(client.fetchRatesForCurrencies(
            DATE,
            List.of("RUB")
        )).thenReturn(List.of(
            quote("RUB", "105.56")
        ));

        mockMvc.perform(
                get("/api/exchange-rates/history")
                    .param("date", DATE.toString())
                    .param("currencies", "USD,RUB")
                    .with(user("history_test"))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rates.length()").value(2));

        verify(client).fetchRatesForCurrencies(
            DATE,
            List.of("RUB")
        );

        assertEquals(
            Long.valueOf(2),
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM historical_exchange_rate",
                Long.class
            )
        );
    }

    @Test
    void incompleteResponseIsNotSaved() throws Exception {
        when(client.fetchRatesForCurrencies(
            DATE,
            List.of("RUB", "USD")
        )).thenReturn(List.of(
            quote("USD", "1.0299")
        ));

        mockMvc.perform(
                get("/api/exchange-rates/history")
                    .param("date", DATE.toString())
                    .param("currencies", "USD,RUB")
                    .with(user("history_test"))
            )
            .andExpect(status().isServiceUnavailable());

        assertEquals(
            Long.valueOf(0),
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM historical_exchange_rate",
                Long.class
            )
        );
    }

    @Test
    void euroDoesNotRequireDatabaseRowsOrProvider() throws Exception {
        mockMvc.perform(
                get("/api/exchange-rates/history")
                    .param("date", DATE.toString())
                    .param("currencies", "EUR")
                    .with(user("history_test"))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rates[0].currencyCode")
                .value("EUR"))
            .andExpect(jsonPath("$.rates[0].rate").value(1));

        verifyNoInteractions(client);

        assertEquals(
            Long.valueOf(0),
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM historical_exchange_rate",
                Long.class
            )
        );
    }

    @Test
    void batchStoresOnlyNeededDatesAndReadsThemFromCache() {
        LocalDate last = DATE.plusDays(2);
        when(client.fetchRatesForRange(DATE, last, List.of("USD")))
            .thenReturn(List.of(
                quote("USD", "2"),
                new ExchangeRateQuote("USD", new BigDecimal("3"), DATE.plusDays(1)),
                new ExchangeRateQuote("USD", new BigDecimal("4"), last)
            ));

        var requests = Map.of(
            DATE, List.of("EUR", "USD"),
            last, List.of("EUR", "USD")
        );
        var first = service.getRatesForDates(requests);
        var second = service.getRatesForDates(requests);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(Long.valueOf(2), jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM historical_exchange_rate", Long.class
        ));
        assertEquals(Long.valueOf(0), jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM historical_exchange_rate WHERE requested_date = ?",
            Long.class, java.sql.Date.valueOf(DATE.plusDays(1))
        ));
        verify(client, times(1)).fetchRatesForRange(DATE, last, List.of("USD"));
        verifyNoMoreInteractions(client);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(
                get("/api/exchange-rates/history")
                    .param("date", DATE.toString())
                    .param("currencies", "USD,RUB")
            )
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(client);
    }
}
