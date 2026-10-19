package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void returnsOnlyAuthenticatedUsersAccounts() throws Exception {
        User owner = createUser("api_account_owner");
        User otherUser = createUser("api_other_owner");

        Account ownAccount = accountRepository.save(
            new Account(
                "Own account",
                new BigDecimal("100.00"),
                "Personal",
                owner
            )
        );

        accountRepository.save(
            new Account(
                "Foreign account",
                new BigDecimal("999.00"),
                "Private",
                otherUser
            )
        );

        mockMvc.perform(get("/api/accounts")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id")
                .value(ownAccount.getAccount_id().intValue()))
            .andExpect(jsonPath("$[0].name").value("Own account"))
            .andExpect(jsonPath("$[0].amount").value(100.00))
            .andExpect(jsonPath("$[0].description").value("Personal"))
            .andExpect(jsonPath("$[0].user").doesNotExist())
            .andExpect(jsonPath("$[0].entries").doesNotExist())
            .andExpect(jsonPath("$[0].fromTransfers").doesNotExist())
            .andExpect(jsonPath("$[0].toTransfers").doesNotExist())
            .andExpect(jsonPath("$[0].syncId")
                .value(ownAccount.getSyncId().toString()));
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused-in-this-test");

        return userRepository.save(user);
    }

    @Test
    void createsAccountForAuthenticatedUser() throws Exception {
        User owner = createUser("api_create_owner");

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Bank account",
                    "amount": 250.00,
                    "description": "Main account"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Bank account"))
            .andExpect(jsonPath("$.amount").value(250.00))
            .andExpect(jsonPath("$.description").value("Main account"))
            .andExpect(jsonPath("$.user").doesNotExist());

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());

        Account saved = accounts.get(0);
        assertNotNull(saved.getSyncId());
        assertEquals("Bank account", saved.getName());
        assertEquals(owner.getId(), saved.getUser().getId());
        assertEquals(
            0,
            new BigDecimal("250.00").compareTo(saved.getAmount())
        );
    }

    @Test
    void rejectsBlankAccountNameWithoutSaving() throws Exception {
        User owner = createUser("api_invalid_account_owner");

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "   ",
                    "amount": 100.00,
                    "description": "Invalid account"
                    }
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                    MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Account name is required"));

        assertEquals(0, accountRepository.findByUser(owner).size());
    }

    @Test
    void createsAccountWithZeroBalanceWhenAmountIsMissing() throws Exception {
        User owner = createUser("api_zero_balance_owner");

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Cash",
                    "description": "New account"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value(0));

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(accounts.get(0).getAmount())
        );
    }

    @Test
    void deletesOwnEmptyAccount() throws Exception {
        User owner = createUser("api_delete_owner");

        Account account = accountRepository.save(
            new Account("Empty account", BigDecimal.ZERO, "", owner)
        );

        mockMvc.perform(delete("/api/accounts/{id}", account.getAccount_id())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        assertTrue(
            accountRepository.findById(account.getAccount_id()).isEmpty()
        );
    }

    @Test
    void rejectsDeletingForeignAccount() throws Exception {
        User owner = createUser("api_delete_actual_owner");
        User otherUser = createUser("api_delete_other_user");

        Account account = accountRepository.save(
            new Account("Private account", BigDecimal.ZERO, "", owner)
        );

        mockMvc.perform(delete("/api/accounts/{id}", account.getAccount_id())
                .with(user(otherUser.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(
            accountRepository.findById(account.getAccount_id()).isPresent()
        );
    }

    @Test
    void rejectsDeletingAccountWithTransfer() throws Exception {
        User owner = createUser("api_delete_transfer_owner");

        Account source = accountRepository.save(
            new Account("Source", new BigDecimal("100"), "", owner)
        );
        Account destination = accountRepository.save(
            new Account("Destination", BigDecimal.ZERO, "", owner)
        );

        Transfer transfer = transferRepository.saveAndFlush(
            new Transfer(
                source, destination, new BigDecimal("10"), "Payment"
            )
        );

        // Synchronize the in-memory relationship used by the service.
        source.getFromTransfers().add(transfer);
        destination.getToTransfers().add(transfer);

        mockMvc.perform(delete("/api/accounts/{id}", source.getAccount_id())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isConflict());

        assertTrue(
            accountRepository.findById(source.getAccount_id()).isPresent()
        );
    }

    @Test
    void createsAccountWithClientSyncId() throws Exception {
        User owner = createUser("api_account_sync_owner");

        UUID syncId = UUID.randomUUID();

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Offline account",
                        "amount": 0,
                        "description": "Created on phone",
                        "syncId": "%s"
                    }
                    """.formatted(syncId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()));

        accountRepository.flush();

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());

        Account saved = accounts.get(0);

        assertEquals(syncId, saved.getSyncId());
        assertEquals(owner.getId(), saved.getUser().getId());
    }

    @Test
    void rejectsDuplicateAccountSyncIdWithoutCreatingAnotherAccount()
            throws Exception {

        User owner = createUser("api_duplicate_sync_owner");

        Account existing = accountRepository.saveAndFlush(
            new Account(
                "Existing account",
                BigDecimal.ZERO,
                "",
                owner
            )
        );

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Duplicate account",
                        "amount": 100,
                        "description": "",
                        "syncId": "%s"
                    }
                    """.formatted(existing.getSyncId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message")
                .value("Account sync ID is already in use"));

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals("Existing account", accounts.get(0).getName());
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(accounts.get(0).getAmount())
        );
    }

    @Test
    void rejectsForeignAccountSyncIdWithoutChangingAccounts()
            throws Exception {

        User owner = createUser("api_sync_actual_owner");
        User otherUser = createUser("api_sync_other_user");

        Account existing = accountRepository.saveAndFlush(
            new Account(
                "Private account",
                new BigDecimal("100.00"),
                "Private description",
                owner
            )
        );

        mockMvc.perform(post("/api/accounts")
                .with(user(otherUser.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Attempted duplicate",
                        "amount": 999,
                        "description": "",
                        "syncId": "%s"
                    }
                    """.formatted(existing.getSyncId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message")
                .value("Account sync ID is already in use"))
            .andExpect(jsonPath("$.name").doesNotExist())
            .andExpect(jsonPath("$.amount").doesNotExist())
            .andExpect(jsonPath("$.description").doesNotExist());

        assertEquals(
            0,
            accountRepository.findByUser(otherUser).size()
        );

        var ownerAccounts = accountRepository.findByUser(owner);

        assertEquals(1, ownerAccounts.size());

        Account unchanged = ownerAccounts.get(0);

        assertEquals(existing.getAccount_id(), unchanged.getAccount_id());
        assertEquals("Private account", unchanged.getName());
        assertEquals("Private description", unchanged.getDescription());
        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(unchanged.getAmount())
        );
    }
}