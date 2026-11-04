package fi.haagahelia.accounting.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateResponse;
import fi.haagahelia.accounting.service.HistoricalExchangeRateService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoricalExchangeRateSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HistoricalExchangeRateService service;

    @Test
    void anonymousUserCanReadHistoricalRates() throws Exception {
        LocalDate date = LocalDate.of(2025, 1, 18);
        when(service.getRates(date, List.of("USD")))
            .thenReturn(new HistoricalExchangeRateResponse(
                date, "EUR", "Frankfurter",
                List.of(new HistoricalExchangeRateQuote(
                    "USD", new BigDecimal("1.0299"), date,
                    Instant.parse("2026-11-04T10:00:00Z")
                ))
            ));

        mockMvc.perform(get("/api/exchange-rates/history")
                .param("date", date.toString())
                .param("currencies", "USD"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestedDate").value("2025-01-18"))
            .andExpect(jsonPath("$.rates[0].currencyCode").value("USD"));
    }

    @Test
    void anonymousUserStillCannotReadAccounts() throws Exception {
        mockMvc.perform(get("/api/accounts"))
            .andExpect(status().isUnauthorized());
    }
}
