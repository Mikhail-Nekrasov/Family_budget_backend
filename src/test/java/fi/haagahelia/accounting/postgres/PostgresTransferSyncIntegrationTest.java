package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresTransferSyncIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void repeatedTransferMovesMoneyOnceAndPreservesClientDate()
            throws Exception {

        User owner = new User();
        owner.setUsername("transfer_sync_test_owner");
        owner.setPassword("unused-in-this-test");
        owner = userRepository.saveAndFlush(owner);

        Account source = accountRepository.saveAndFlush(
            new Account(
                "Source", new BigDecimal("100.00"), "", owner
            )
        );

        Account destination = accountRepository.saveAndFlush(
            new Account(
                "Destination", new BigDecimal("100.00"), "", owner
            )
        );

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        LocalDateTime clientDate = LocalDateTime.of(
            2026, 10, 10, 12, 30
        );

        long transfersBefore = transferRepository.count();

        String requestBody = """
            {
                "syncId": "%s",
                "fromAccountSyncId": "%s",
                "toAccountSyncId": "%s",
                "amount": 75.00,
                "dateTime": "2026-10-10T12:30:00",
                "description": "Offline transfer"
            }
            """.formatted(
                syncId,
                source.getSyncId(),
                destination.getSyncId()
            );

        String firstResponse = mockMvc.perform(
                post("/api/sync/transfers/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andExpect(jsonPath("$.amount").value(75))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

        transferRepository.flush();

        mockMvc.perform(
                post("/api/sync/transfers/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        assertEquals(
            transfersBefore + 1,
            transferRepository.count()
        );

        var saved = transferRepository.findBySyncId(syncId)
            .orElseThrow();

        assertEquals(clientDate, saved.getDateTime());

        assertEquals(
            0,
            new BigDecimal("25.00").compareTo(
                accountRepository.findById(source.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            0,
            new BigDecimal("175.00").compareTo(
                accountRepository.findById(
                    destination.getAccount_id()
                ).orElseThrow().getAmount()
            )
        );
    }


    @Test
    void editsAndDeletesCrossCurrencyTransferExactlyOnce() throws Exception {
        User owner = saveUser("editable_transfer_owner");
        Account cash = saveAccount(owner, "Cash", "EUR", "100.00");
        Account yen = saveAccount(owner, "Yen", "JPY", "5000");
        Account holiday = saveAccount(owner, "Holiday", "EUR", "200.00");
        UUID transfer = UUID.randomUUID();

        send(owner, post("/api/sync/transfers/{commandId}", UUID.randomUUID()),
            transferBody(transfer, cash, yen, "20.25", "3000", null))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.accountVersionChanges.length()").value(2))
            .andExpect(jsonPath("$.accountVersionChanges[0].previousVersion").value(0))
            .andExpect(jsonPath("$.accountVersionChanges[0].version").value(1));

        assertBalance(cash, "79.75");
        assertBalance(yen, "8000");
        UUID editCommand = UUID.randomUUID();
        String edit = transferBody(transfer, yen, holiday, "1250", "7.55", 0L);

        String edited = send(owner, put("/api/sync/transfers/{commandId}", editCommand), edit)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.sourceCurrencyCode").value("JPY"))
            .andExpect(jsonPath("$.targetCurrencyCode").value("EUR"))
            .andExpect(jsonPath("$.accountVersionChanges.length()").value(3))
            .andExpect(jsonPath("$.accountVersionChanges[1].accountSyncId").value(yen.getSyncId().toString()))
            .andExpect(jsonPath("$.accountVersionChanges[1].previousVersion").value(1))
            .andExpect(jsonPath("$.accountVersionChanges[1].version").value(2))
            .andReturn().getResponse().getContentAsString();
        send(owner, put("/api/sync/transfers/{commandId}", editCommand), edit)
            .andExpect(status().isOk()).andExpect(content().string(edited));
        send(owner, put("/api/sync/transfers/{commandId}", editCommand),
            transferBody(transfer, yen, holiday, "1250", "7.56", 0L))
            .andExpect(status().isConflict());
        send(owner, put("/api/sync/transfers/{commandId}", UUID.randomUUID()), edit)
            .andExpect(status().isConflict());
        assertBalance(cash, "100.00");
        assertBalance(yen, "3750");
        assertBalance(holiday, "207.55");
        assertEquals(1L, jdbc.queryForObject("""
            SELECT (payload->>'version')::bigint FROM sync_personal_entity
            WHERE user_id = ? AND entity_type = 'TRANSFER' AND sync_id = ?
            """, Long.class, owner.getId(), transfer));

        send(owner, post("/api/sync/transfers/{commandId}/delete", UUID.randomUUID()),
            "{\"syncId\":\"%s\",\"expectedVersion\":0}".formatted(transfer))
            .andExpect(status().isConflict());
        UUID deleteCommand = UUID.randomUUID();
        String delete = "{\"syncId\":\"%s\",\"expectedVersion\":1}".formatted(transfer);
        String deleted = send(owner, post("/api/sync/transfers/{commandId}/delete", deleteCommand), delete)
            .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true))
            .andExpect(jsonPath("$.accountVersionChanges.length()").value(2))
            .andExpect(jsonPath("$.accountVersionChanges[0].previousVersion").value(2))
            .andExpect(jsonPath("$.accountVersionChanges[0].version").value(3))
            .andReturn().getResponse().getContentAsString();
        send(owner, post("/api/sync/transfers/{commandId}/delete", deleteCommand), delete)
            .andExpect(status().isOk()).andExpect(content().string(deleted));
        send(owner, post("/api/sync/transfers/{commandId}", UUID.randomUUID()),
            transferBody(transfer, cash, yen, "20.25", "3000", null))
            .andExpect(status().isConflict());
        assertEquals(1L, jdbc.queryForObject("""
            SELECT COUNT(*) FROM sync_tombstone
            WHERE user_id = ? AND entity_type = 'TRANSFER' AND sync_id = ?
            """, Long.class, owner.getId(), transfer));
        assertEquals(0L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer WHERE sync_id = ?", Long.class, transfer));
        assertBalance(cash, "100.00");
        assertBalance(yen, "5000");
        assertBalance(holiday, "200.00");
    }

    @Test
    void rejectsForeignAccountsAndInvalidPrecisionWithoutChangingBalances() throws Exception {
        User owner = saveUser("protected_transfer_owner");
        User other = saveUser("protected_transfer_other");
        Account euros = saveAccount(owner, "Euros", "EUR", "100");
        Account yen = saveAccount(owner, "Yen", "JPY", "5000");
        Account foreign = saveAccount(other, "Foreign", "EUR", "200");
        UUID transfer = UUID.randomUUID();

        send(owner, post("/api/sync/transfers/{commandId}", UUID.randomUUID()),
            transferBody(transfer, euros, yen, "20", "3000", null))
            .andExpect(status().isCreated());

        send(other, post("/api/sync/transfers/{commandId}/delete", UUID.randomUUID()),
            "{\"syncId\":\"%s\",\"expectedVersion\":0}".formatted(transfer))
            .andExpect(status().isNotFound());
        send(owner, put("/api/sync/transfers/{commandId}", UUID.randomUUID()),
            transferBody(transfer, foreign, yen, "20", "3000", 0L))
            .andExpect(status().isBadRequest());
        send(owner, put("/api/sync/transfers/{commandId}", UUID.randomUUID()),
            transferBody(transfer, euros, yen, "20", "3000.01", 0L))
            .andExpect(status().isBadRequest());

        assertBalance(euros, "80");
        assertBalance(yen, "8000");
        assertBalance(foreign, "200");
        assertEquals(0L, transferRepository.findBySyncId(transfer).orElseThrow().getVersion());
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused-in-this-test");
        return userRepository.saveAndFlush(user);
    }

    private Account saveAccount(User user, String name, String currency, String balance) {
        Account account = new Account(name, new BigDecimal(balance), "", user);
        account.setCurrencyCode(currency);
        return accountRepository.saveAndFlush(account);
    }

    private ResultActions send(User user, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(user(user.getUsername())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String transferBody(
            UUID transfer, Account from, Account to, String amount, String credit, Long version) {
        String versionField = version == null ? "" : ",\"expectedVersion\":" + version;
        return """
            {"syncId":"%s","fromAccountSyncId":"%s","toAccountSyncId":"%s",
             "amount":%s,"targetAmount":%s,"description":"Offline transfer",
             "dateTime":"2026-11-16T00:30:00"%s}
            """.formatted(transfer, from.getSyncId(), to.getSyncId(), amount, credit, versionField);
    }

    private void assertBalance(Account account, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(
            "SELECT amount FROM account WHERE account_id = ?", BigDecimal.class, account.getAccount_id()
        )));
    }
}