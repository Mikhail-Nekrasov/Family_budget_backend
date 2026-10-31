package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
import fi.haagahelia.accounting.repository.ExchangeRateRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresExchangeRateApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeRateRepository repository;

    @MockitoBean
    private FrankfurterClient client;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM exchange_rate");

        jdbcTemplate.update("""
            UPDATE exchange_rate_state
            SET fetched_at = NULL
            WHERE singleton_id = 1
            """);
    }

    @Test
    void publicEndpointLoadsPersistsAndReusesRates() throws Exception {
        when(client.fetchLatestRates()).thenReturn(List.of(
            new ExchangeRateQuote(
                "EUR",
                BigDecimal.ONE,
                LocalDate.of(2026, 10, 30)
            ),
            new ExchangeRateQuote(
                "RUB",
                new BigDecimal("90.14"),
                LocalDate.of(2026, 10, 29)
            )
        ));

        mockMvc.perform(get("/api/exchange-rates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.baseCurrency").value("EUR"))
            .andExpect(jsonPath("$.provider").value("Frankfurter"))
            .andExpect(jsonPath("$.fetchedAt").isNotEmpty())
            .andExpect(jsonPath("$.rates.length()").value(2))
            .andExpect(jsonPath(
                "$.rates[?(@.currencyCode == 'RUB')].rate",
                org.hamcrest.Matchers.hasItem(90.14)
            ))
            .andExpect(jsonPath(
                "$.rates[?(@.currencyCode == 'RUB')].rateDate",
                org.hamcrest.Matchers.hasItem("2026-10-29")
            ));

        var stored = repository.findSnapshot().orElseThrow();

        assertEquals(2, stored.rates().size());

        var rub = stored.rates().stream()
            .filter(rate -> rate.currencyCode().equals("RUB"))
            .findFirst()
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("90.14").compareTo(rub.rate())
        );
        assertEquals(
            LocalDate.of(2026, 10, 29),
            rub.rateDate()
        );

        mockMvc.perform(get("/api/exchange-rates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rates.length()").value(2));

        verify(client, times(1)).fetchLatestRates();
    }
}