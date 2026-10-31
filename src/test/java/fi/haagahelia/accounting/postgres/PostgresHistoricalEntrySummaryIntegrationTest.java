package fi.haagahelia.accounting.postgres;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateResponse;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.HistoricalExchangeRateService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresHistoricalEntrySummaryIntegrationTest {

    private static final LocalDate FIRST =
        LocalDate.of(2025, 1, 10);

    private static final LocalDate SECOND =
        LocalDate.of(2025, 1, 11);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EntryRepository entryRepository;

    @MockitoBean
    private HistoricalExchangeRateService historicalRates;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = createUser();
    }

    private User createUser() {
        User created = new User();
        created.setUsername("historical_summary_" + UUID.randomUUID());
        created.setPassword("unused-in-this-test");

        return userRepository.saveAndFlush(created);
    }

    private void createEntry(
            User entryOwner,
            EntryType type,
            String currency,
            String amount,
            LocalDate date) {

        Account account = new Account(
            "Account " + UUID.randomUUID(),
            new BigDecimal("1000.00"),
            "",
            entryOwner
        );
        account.setCurrencyCode(currency);
        account = accountRepository.saveAndFlush(account);

        Category category = categoryRepository.saveAndFlush(
            new Category(
                "Category " + UUID.randomUUID(),
                type,
                entryOwner
            )
        );

        entryRepository.saveAndFlush(
            new Entry(
                "Operation",
                type,
                new BigDecimal(amount),
                date.atTime(12, 0),
                "",
                category,
                account,
                entryOwner
            )
        );
    }

    private HistoricalExchangeRateResponse rates(
            LocalDate date,
            String usdRate) {

        Instant fetchedAt =
            Instant.parse("2026-10-31T12:00:00Z");

        return new HistoricalExchangeRateResponse(
            date,
            "EUR",
            "Frankfurter",
            List.of(
                new HistoricalExchangeRateQuote(
                    "EUR",
                    BigDecimal.ONE,
                    date,
                    fetchedAt
                ),
                new HistoricalExchangeRateQuote(
                    "USD",
                    new BigDecimal(usdRate),
                    date,
                    fetchedAt
                )
            )
        );
    }

    @Test
    void usesOperationDateRatesAndExcludesForeignEntries()
            throws Exception {

        createEntry(owner, EntryType.EXPENSE, "USD", "100", FIRST);
        createEntry(owner, EntryType.EXPENSE, "USD", "100", SECOND);
        createEntry(owner, EntryType.INCOME, "EUR", "40", FIRST);

        createEntry(
            createUser(),
            EntryType.EXPENSE,
            "EUR",
            "999",
            FIRST
        );

        when(historicalRates.getRates(
            FIRST,
            List.of("EUR", "USD")
        )).thenReturn(rates(FIRST, "2"));

        when(historicalRates.getRates(
            SECOND,
            List.of("EUR", "USD")
        )).thenReturn(rates(SECOND, "4"));

        mockMvc.perform(
                get("/api/entries/summary")
                    .param("currency", "EUR")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencyCode").value("EUR"))
            .andExpect(jsonPath("$.totalIncome").value(40))
            .andExpect(jsonPath("$.totalExpense").value(75))
            .andExpect(jsonPath("$.netAmount").value(-35));

        verify(historicalRates).getRates(
            FIRST,
            List.of("EUR", "USD")
        );

        verify(historicalRates).getRates(
            SECOND,
            List.of("EUR", "USD")
        );
    }

    @Test
    void roundsOnlyAfterCombiningDifferentDays() throws Exception {
        for (int day = 0; day < 3; day++) {
            LocalDate date = FIRST.plusDays(day);

            createEntry(
                owner,
                EntryType.EXPENSE,
                "USD",
                "0.01",
                date
            );

            when(historicalRates.getRates(
                date,
                List.of("EUR", "USD")
            )).thenReturn(rates(date, "3"));
        }

        mockMvc.perform(
                get("/api/entries/summary")
                    .param("currency", "EUR")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalExpense").value(0.01))
            .andExpect(jsonPath("$.netAmount").value(-0.01));
    }

    @Test
    void emptySummaryDoesNotRequestHistoricalRates()
            throws Exception {

        mockMvc.perform(
                get("/api/entries/summary")
                    .param("currency", "USD")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencyCode").value("USD"))
            .andExpect(jsonPath("$.totalIncome").value(0))
            .andExpect(jsonPath("$.totalExpense").value(0))
            .andExpect(jsonPath("$.netAmount").value(0));

        verifyNoInteractions(historicalRates);
    }

    @Test
    void sameCurrencyDoesNotRequestHistoricalRates()
            throws Exception {

        createEntry(
            owner,
            EntryType.EXPENSE,
            "USD",
            "25.50",
            FIRST
        );

        mockMvc.perform(
                get("/api/entries/summary")
                    .param("currency", "USD")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalExpense").value(25.50));

        verifyNoInteractions(historicalRates);
    }

    @Test
    void webPageRendersHistoricalTotal() throws Exception {
        createEntry(
            owner,
            EntryType.EXPENSE,
            "USD",
            "100",
            FIRST
        );

        when(historicalRates.getRates(
            FIRST,
            List.of("EUR", "USD")
        )).thenReturn(rates(FIRST, "2"));

        mockMvc.perform(
                get("/entries")
                    .param("type", "EXPENSE")
                    .param("currency", "EUR")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "Total expenses: -50.00 EUR"
                )
            ));
    }

    @Test
    void unavailableRatesDoNotPreventViewingEntries()
            throws Exception {

        createEntry(
            owner,
            EntryType.EXPENSE,
            "USD",
            "100",
            FIRST
        );

        when(historicalRates.getRates(
            FIRST,
            List.of("EUR", "USD")
        )).thenThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Historical exchange rate is unavailable"
        ));

        mockMvc.perform(
                get("/api/entries/summary")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isServiceUnavailable());

        mockMvc.perform(
                get("/entries")
                    .param("type", "EXPENSE")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "Historical totals are temporarily unavailable"
                )
            ))
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "Add new expense"
                )
            ));
    }
}