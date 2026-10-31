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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.ExchangeRateService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresConvertedBalanceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = createUser();
    }

    private User createUser() {
        User created = new User();
        created.setUsername("converted_balance_" + UUID.randomUUID());
        created.setPassword("unused-in-this-test");

        return userRepository.saveAndFlush(created);
    }

    private Account createAccount(
            User accountOwner,
            String currency,
            String amount) {

        Account account = new Account(
            "Account " + UUID.randomUUID(),
            new BigDecimal(amount),
            "",
            accountOwner
        );
        account.setCurrencyCode(currency);

        return accountRepository.saveAndFlush(account);
    }

    private void createMixedAccounts() {
        createAccount(owner, "EUR", "100.00");

        Account archived = createAccount(owner, "USD", "11.00");
        archived.setArchivedAt(Instant.now());
        accountRepository.saveAndFlush(archived);

        createAccount(owner, "RUB", "1000.00");
        createAccount(createUser(), "EUR", "999.00");
    }

    private ExchangeRateSnapshot rates() {
        return new ExchangeRateSnapshot(
            "EUR",
            "Frankfurter",
            Instant.parse("2026-10-31T12:00:00Z"),
            List.of(
                new ExchangeRateQuote(
                    "EUR",
                    BigDecimal.ONE,
                    LocalDate.of(2026, 10, 30)
                ),
                new ExchangeRateQuote(
                    "USD",
                    new BigDecimal("1.10"),
                    LocalDate.of(2026, 10, 30)
                ),
                new ExchangeRateQuote(
                    "RUB",
                    new BigDecimal("100"),
                    LocalDate.of(2026, 10, 30)
                )
            )
        );
    }

    @ParameterizedTest
    @CsvSource({
        "EUR, 120.00",
        "USD, 132.00",
        "RUB, 12000.00"
    })
    void returnsOneConvertedTotal(
            String currency,
            String expectedAmount) throws Exception {

        createMixedAccounts();

        when(exchangeRateService.getLatestRates())
            .thenReturn(rates());

        mockMvc.perform(
                get("/api/accounts/balance")
                    .param("currency", currency)
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencyCode").value(currency))
            .andExpect(jsonPath("$.amount").value(
                new BigDecimal(expectedAmount).doubleValue()
            ))
            .andExpect(jsonPath("$.ratesFetchedAt").isNotEmpty());

        verify(exchangeRateService, times(1)).getLatestRates();
    }

    @Test
    void singleCurrencyDoesNotRequireRateProvider() throws Exception {
        createAccount(owner, "USD", "25.50");

        mockMvc.perform(
                get("/api/accounts/balance")
                    .param("currency", "USD")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(25.50))
            .andExpect(jsonPath("$.ratesFetchedAt").value(
                org.hamcrest.Matchers.nullValue()
            ));

        verifyNoInteractions(exchangeRateService);
    }

    @Test
    void emptyAccountsReturnZeroWithoutRates() throws Exception {
        mockMvc.perform(
                get("/api/accounts/balance")
                    .param("currency", "USD")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(0));

        verifyNoInteractions(exchangeRateService);
    }

    @Test
    void webPageRendersConvertedTotal() throws Exception {
        createMixedAccounts();

        when(exchangeRateService.getLatestRates())
            .thenReturn(rates());

        mockMvc.perform(
                get("/accounts")
                    .param("currency", "EUR")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "Total: 120.00 EUR"
                )
            ));
    }

    @Test
    void missingRatesDoNotPreventOpeningWebAccounts() throws Exception {
        createMixedAccounts();

        when(exchangeRateService.getLatestRates())
            .thenThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Exchange rates are temporarily unavailable"
            ));

        mockMvc.perform(
                get("/accounts")
                    .with(user(owner.getUsername()))
            )
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "Total balance is temporarily unavailable"
                )
            ));
    }

    @Test
    void balanceRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/accounts/balance"))
            .andExpect(status().isUnauthorized());
    }
}