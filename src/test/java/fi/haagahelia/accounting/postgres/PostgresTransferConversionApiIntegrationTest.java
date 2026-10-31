package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.api.dto.CreateTransferRequest;
import fi.haagahelia.accounting.api.dto.SyncCreateTransferRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresTransferConversionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private User owner;
    private Account source;
    private Account destination;
    private UUID commandId;
    private UUID transferSyncId;

    @BeforeEach
    void setUp() {
        User created = new User();
        created.setUsername("conversion_" + UUID.randomUUID());
        created.setPassword("unused-in-this-test");
        owner = userRepository.saveAndFlush(created);

        Account sourceAccount = new Account(
            "EUR account",
            new BigDecimal("1000.00"),
            "",
            owner
        );
        sourceAccount.setCurrencyCode("EUR");
        source = accountRepository.saveAndFlush(sourceAccount);

        Account destinationAccount = new Account(
            "USD account",
            new BigDecimal("50.00"),
            "",
            owner
        );
        destinationAccount.setCurrencyCode("USD");
        destination = accountRepository.saveAndFlush(destinationAccount);

        commandId = UUID.randomUUID();
        transferSyncId = UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder conversionRequest(
            boolean sync,
            @Nullable BigDecimal targetAmount) {

        String path;
        String body;

        if (sync) {
            path = "/api/sync/transfers/" + commandId;

            body = jsonMapper.writeValueAsString(
                new SyncCreateTransferRequest(
                    transferSyncId,
                    source.getSyncId(),
                    destination.getSyncId(),
                    new BigDecimal("100.00"),
                    LocalDateTime.of(2026, 10, 30, 12, 0),
                    "Conversion",
                    targetAmount
                )
            );
        } else {
            path = "/api/transfers";

            body = jsonMapper.writeValueAsString(
                new CreateTransferRequest(
                    source.getAccount_id(),
                    destination.getAccount_id(),
                    new BigDecimal("100.00"),
                    "Conversion",
                    transferSyncId,
                    targetAmount
                )
            );
        }

        return post(path)
            .with(user(owner.getUsername()))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private void assertBalances(
            String expectedSource,
            String expectedDestination) {

        accountRepository.flush();

        BigDecimal sourceBalance = Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT amount FROM account WHERE account_id = ?",
                BigDecimal.class,
                source.getAccount_id()
            )
        );

        BigDecimal destinationBalance = Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "SELECT amount FROM account WHERE account_id = ?",
                BigDecimal.class,
                destination.getAccount_id()
            )
        );

        assertEquals(
            0,
            new BigDecimal(expectedSource).compareTo(sourceBalance)
        );
        assertEquals(
            0,
            new BigDecimal(expectedDestination)
                .compareTo(destinationBalance)
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void convertsAndReturnsBothAmounts(boolean sync) throws Exception {
        var result = mockMvc.perform(
                conversionRequest(sync, new BigDecimal("108.50")))
            .andExpect(status().isCreated());

        if (sync) {
            result
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.targetAmount").value(108.50))
                .andExpect(jsonPath("$.sourceCurrencyCode").value("EUR"))
                .andExpect(jsonPath("$.targetCurrencyCode").value("USD"));
        }

        assertBalances("900.00", "158.50");

        mockMvc.perform(get("/api/transfers")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].amount").value(100.00))
            .andExpect(jsonPath("$.content[0].targetAmount").value(108.50))
            .andExpect(jsonPath("$.content[0].sourceCurrencyCode")
                .value("EUR"))
            .andExpect(jsonPath("$.content[0].targetCurrencyCode")
                .value("USD"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingDestinationAmount(boolean sync) throws Exception {
        mockMvc.perform(conversionRequest(sync, null))
            .andExpect(status().isBadRequest());

        assertBalances("1000.00", "50.00");

        assertTrue(
            transferRepository.findBySyncId(transferSyncId).isEmpty()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "108.501"})
    void rejectsInvalidDestinationAmount(String amount) throws Exception {
        mockMvc.perform(
                conversionRequest(false, new BigDecimal(amount)))
            .andExpect(status().isBadRequest());

        assertBalances("1000.00", "50.00");

        assertTrue(
            transferRepository.findBySyncId(transferSyncId).isEmpty()
        );
    }

    @Test
    void repeatedSyncCommandDoesNotConvertTwice() throws Exception {
        String firstResponse = mockMvc.perform(
                conversionRequest(true, new BigDecimal("108.50")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                conversionRequest(true, new BigDecimal("108.50")))
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        assertBalances("900.00", "158.50");

        mockMvc.perform(get("/api/transfers")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void rejectsChangedDestinationAmountForSameCommand() throws Exception {
        mockMvc.perform(
                conversionRequest(true, new BigDecimal("108.50")))
            .andExpect(status().isCreated());

        mockMvc.perform(
                conversionRequest(true, new BigDecimal("109.00")))
            .andExpect(status().isConflict());

        assertBalances("900.00", "158.50");
    }
}