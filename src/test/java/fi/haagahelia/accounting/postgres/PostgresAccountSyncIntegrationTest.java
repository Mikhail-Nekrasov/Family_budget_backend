package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;
import fi.haagahelia.accounting.service.AccountSyncService;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
@AutoConfigureMockMvc
class PostgresAccountSyncIntegrationTest {

    @Autowired
    private AccountSyncService accountSyncService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    private User owner;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("account_sync_test_owner");
        user.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(user);
    }

    @Test
    void repeatedCommandReturnsOriginalResponseWithoutCreatingAnotherAccount() {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        CreateAccountRequest request = new CreateAccountRequest(
            "Cash",
            new BigDecimal("100.00"),
            "Personal account",
            syncId,
            null,
            null
        );

        CompletedCommand first = accountSyncService.createAccount(
            commandId, request, owner
        );

        // A numerically equivalent amount is the same command payload.
        CreateAccountRequest repeatedRequest = new CreateAccountRequest(
            "Cash",
            new BigDecimal("100.0"),
            "Personal account",
            syncId,
            null,
            null
        );

        CompletedCommand repeated = accountSyncService.createAccount(
            commandId, repeatedRequest, owner
        );

        accountRepository.flush();

        assertEquals(201, first.responseStatus());
        assertEquals(first, repeated);

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals(syncId, accounts.get(0).getSyncId());
        assertEquals(
            0,
            new BigDecimal("100.00")
                .compareTo(accounts.get(0).getAmount())
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ?
                """,
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void rejectsSameCommandIdWithDifferentPayload() {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        accountSyncService.createAccount(
            commandId,
            new CreateAccountRequest(
                "Cash",
                BigDecimal.ZERO,
                "",
                syncId,
                null,
                null
            ),
            owner
        );

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> accountSyncService.createAccount(
                commandId,
                new CreateAccountRequest(
                    "Changed name",
                    BigDecimal.ZERO,
                    "",
                    syncId,
                    null,
                    null
                ),
                owner
            )
        );

        assertEquals(
            HttpStatus.CONFLICT,
            exception.getStatusCode()
        );
        assertEquals(
            "Command ID is already used for a different request",
            exception.getReason()
        );

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals("Cash", accounts.get(0).getName());

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ?
                """,
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void repeatedHttpRequestReturnsExactlyTheSameResponse()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        String requestBody = """
            {
                "name": "Offline cash",
                "amount": 100,
                "description": "Created on phone",
                "syncId": "%s"
            }
            """.formatted(syncId);

        String firstResponse = mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        assertEquals(
            1,
            accountRepository.findByUser(owner).size()
        );
    }

    @Test
    void rejectsSyncRequestWithoutSyncId() throws Exception {
        UUID commandId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Missing sync ID",
                            "amount": 0,
                            "description": ""
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "Sync ID is required for synchronization"
            ));

        assertEquals(
            0,
            accountRepository.findByUser(owner).size()
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ?
                """,
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void rejectsAnonymousSyncRequest() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Anonymous account",
                            "amount": 0,
                            "description": "",
                            "syncId": "%s"
                        }
                        """.formatted(syncId))
            )
            .andExpect(status().isUnauthorized());

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE command_id = ?
                """,
                Long.class,
                commandId
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );
    }

    @Test
    void updatesAccountOnceAndRejectsStaleVersion() throws Exception {
        Account account = accountRepository.saveAndFlush(
            new Account(
                "Original account",
                new BigDecimal("100.00"),
                "Original description",
                owner
            )
        );

        UUID commandId = UUID.randomUUID();
        Long originalVersion = account.getVersion();

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d,
                "name": "Renamed account",
                "description": "Updated description",
                "iconKey": "travel-fund"
            }
            """.formatted(
                account.getSyncId(),
                originalVersion
            );

        String firstResponse = mockMvc.perform(
                put("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncId")
                .value(account.getSyncId().toString()))
            .andExpect(jsonPath("$.name").value("Renamed account"))
            .andExpect(jsonPath("$.iconKey").value("travel-fund"))
            .andExpect(jsonPath("$.description")
                .value("Updated description"))
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.version")
                .value(originalVersion.longValue() + 1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // An exact retry returns the stored result.
        mockMvc.perform(
                put("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(content().string(firstResponse));

        // The same command ID cannot be reused with another icon.
        String changedRequestBody = requestBody.replace(
            "\"iconKey\": \"travel-fund\"",
            "\"iconKey\": \"savings\""
        );

        mockMvc.perform(
                put("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changedRequestBody)
            )
            .andExpect(status().isConflict());

        // A new command cannot use the outdated version.
        UUID staleCommandId = UUID.randomUUID();

        mockMvc.perform(
                put("/api/sync/accounts/{commandId}", staleCommandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Account version conflict. Synchronize before retrying."
            ));

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());

        Account updated = accounts.get(0);

        assertEquals(account.getAccount_id(), updated.getAccount_id());
        assertEquals("Renamed account", updated.getName());
        assertEquals("Updated description", updated.getDescription());
        assertEquals("travel-fund", updated.getIconKey());
        assertEquals(
            originalVersion.longValue() + 1,
            updated.getVersion().longValue()
        );
        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(updated.getAmount())
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                commandId
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                staleCommandId
            )
        );
    }

    @Test
    void updatingAccountWithoutIconKeyPreservesExistingIcon() throws Exception {
        Account account = new Account(
            "Cash",
            new BigDecimal("100.00"),
            "",
            owner
        );
        account.setIconKey("travel-fund");
        account = accountRepository.saveAndFlush(account);

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d,
                "name": "Renamed account",
                "description": ""
            }
            """.formatted(
                account.getSyncId(),
                account.getVersion()
            );

        mockMvc.perform(
                put("/api/sync/accounts/{commandId}", UUID.randomUUID())
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed account"))
            .andExpect(jsonPath("$.iconKey").value("travel-fund"));

        Account updated = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals("travel-fund", updated.getIconKey());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 65})
    void rejectsInvalidIconKeyWithoutChangingAccount(int length)
            throws Exception {

        Account account = new Account(
            "Original account",
            new BigDecimal("100.00"),
            "Original description",
            owner
        );
        account.setIconKey("card");
        account = accountRepository.saveAndFlush(account);

        Long originalVersion = account.getVersion();

        String iconKey = length == 1
            ? " "
            : "x".repeat(length);

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d,
                "name": "Changed account",
                "description": "Changed description",
                "iconKey": "%s"
            }
            """.formatted(
                account.getSyncId(),
                originalVersion,
                iconKey
            );

        mockMvc.perform(
                put("/api/sync/accounts/{commandId}", UUID.randomUUID())
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isBadRequest());

        Account unchanged = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals("Original account", unchanged.getName());
        assertEquals(
            "Original description",
            unchanged.getDescription()
        );
        assertEquals("card", unchanged.getIconKey());
        assertEquals(originalVersion, unchanged.getVersion());
        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(unchanged.getAmount())
        );
    }

    @Test
    void rejectsUpdatingAnotherUsersAccount() throws Exception {
        User otherUser = new User();
        otherUser.setUsername("account_sync_other_owner");
        otherUser.setPassword("unused-in-this-test");
        otherUser = userRepository.saveAndFlush(otherUser);

        Account account = accountRepository.saveAndFlush(
            new Account(
                "Private account",
                new BigDecimal("100.00"),
                "Original description",
                otherUser
            )
        );

        Long originalVersion = account.getVersion();
        UUID commandId = UUID.randomUUID();

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d,
                "name": "Changed by another user",
                "description": "Unauthorized change"
            }
            """.formatted(account.getSyncId(), originalVersion);

        mockMvc.perform(
                put("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value(
                "Account is unavailable for synchronization"
            ));

        // Check the stored values directly, bypassing Hibernate's cache.
        assertEquals(
            "Private account",
            jdbcTemplate.queryForObject(
                "SELECT name FROM account WHERE account_id = ?",
                String.class,
                account.getAccount_id()
            )
        );

        assertEquals(
            "Original description",
            jdbcTemplate.queryForObject(
                "SELECT description FROM account WHERE account_id = ?",
                String.class,
                account.getAccount_id()
            )
        );

        assertEquals(
            originalVersion,
            jdbcTemplate.queryForObject(
                "SELECT version FROM account WHERE account_id = ?",
                Long.class,
                account.getAccount_id()
            )
        );

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(
                jdbcTemplate.queryForObject(
                    "SELECT amount FROM account WHERE account_id = ?",
                    BigDecimal.class,
                    account.getAccount_id()
                )
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                commandId
            )
        );
    }

    @Test
    void deletedAccountCannotBeRecreatedWithTheSameSyncId() {
        Account account = accountRepository.saveAndFlush(
            new Account(
                "Temporary account",
                BigDecimal.ZERO,
                "",
                owner
            )
        );

        UUID syncId = account.getSyncId();

        assertEquals(
            true,
            accountService.deleteAccount(
                account.getAccount_id(), owner
            )
        );

        accountRepository.flush();

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'ACCOUNT'
                AND sync_id = ?
                """,
                Long.class,
                owner.getId(),
                syncId
            )
        );

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> accountSyncService.createAccount(
                UUID.randomUUID(),
                new CreateAccountRequest(
                    "Restored from an old phone",
                    BigDecimal.ZERO,
                    "",
                    syncId,
                    null,
                    null
                ),
                owner
            )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
            "Account was deleted and cannot be recreated with the same sync ID",
            exception.getReason()
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sync_command WHERE user_id = ?",
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void deletesAccountOnceAndReturnsOriginalResponseOnRetry()
            throws Exception {

        Account account = accountRepository.saveAndFlush(
            new Account(
                "Account to delete",
                BigDecimal.ZERO,
                "",
                owner
            )
        );

        UUID syncId = account.getSyncId();
        UUID commandId = UUID.randomUUID();

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d
            }
            """.formatted(syncId, account.getVersion());

        String firstResponse = mockMvc.perform(
                post("/api/sync/accounts/{commandId}/delete", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andExpect(jsonPath("$.deleted").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}/delete", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(content().string(firstResponse));

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'ACCOUNT'
                AND sync_id = ?
                """,
                Long.class,
                owner.getId(),
                syncId
            )
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                commandId
            )
        );
    }

    @Test
    void rejectsDeletingAccountWithStaleVersion() throws Exception {
        Account account = accountRepository.saveAndFlush(
            new Account(
                "Original name",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        UUID syncId = account.getSyncId();
        Long originalVersion = account.getVersion();

        // Simulate a change made after the phone downloaded the account.
        account.setName("Updated on another device");
        accountRepository.saveAndFlush(account);

        Long currentVersion = account.getVersion();
        assertEquals(originalVersion + 1, currentVersion.longValue());

        UUID commandId = UUID.randomUUID();

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d
            }
            """.formatted(syncId, originalVersion);

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}/delete", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Account version conflict. Synchronize before retrying."
            ));

        assertEquals(
            "Updated on another device",
            jdbcTemplate.queryForObject(
                "SELECT name FROM account WHERE sync_id = ?",
                String.class,
                syncId
            )
        );

        assertEquals(
            currentVersion,
            jdbcTemplate.queryForObject(
                "SELECT version FROM account WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(
                jdbcTemplate.queryForObject(
                    "SELECT amount FROM account WHERE sync_id = ?",
                    BigDecimal.class,
                    syncId
                )
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'ACCOUNT'
                AND sync_id = ?
                """,
                Long.class,
                owner.getId(),
                syncId
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                commandId
            )
        );
    }
}