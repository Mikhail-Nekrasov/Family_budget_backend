package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresEntrySyncIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private Account account;
    private Category category;

    @BeforeEach
    void setUp() {
        User accountOwner = new User();
        accountOwner.setUsername("entry_sync_test_owner");
        accountOwner.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(accountOwner);

        account = accountRepository.saveAndFlush(
            new Account(
                "Sync account",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        category = categoryRepository.saveAndFlush(
            new Category("Food", EntryType.EXPENSE, owner)
        );
    }

    @Test
    void restoresLegacyCategoryAtSameSyncIdAndReplaysExpenseOnlyOnce() throws Exception {
        UUID categorySyncId = category.getSyncId();
        deleteFixture("/api/categories/{id}", category.getCategory_id());
        jdbcTemplate.update("UPDATE sync_tombstone SET restore_snapshot = NULL WHERE sync_id = ?", categorySyncId);
        UUID token = dependencyToken("CATEGORY", categorySyncId);
        UUID command = UUID.randomUUID();
        String entry = offlineExpense(account.getSyncId(), categorySyncId);

        sendOfflineExpense(command, entry).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SYNC_DEPENDENCY_DELETED"))
            .andExpect(jsonPath("$.dependency.restoreToken").value(token.toString()))
            .andExpect(jsonPath("$.dependency.needsCategoryDetails").value(true));

        String restore = """
            {"entityType":"CATEGORY","syncId":"%s","restoreToken":"%s",
             "categoryName":"Food","categoryType":"EXPENSE","categoryVersion":0}
            """.formatted(categorySyncId, token);
        restoreDependency(restore).andExpect(status().isOk())
            .andExpect(jsonPath("$.syncId").value(categorySyncId.toString()))
            .andExpect(jsonPath("$.version").value(1));
        restoreDependency(restore).andExpect(status().isOk());
        sendOfflineExpense(command, entry).andExpect(status().isCreated());
        sendOfflineExpense(command, entry).andExpect(status().isCreated());
        assertEquals(0, new BigDecimal("70").compareTo(jdbcTemplate.queryForObject(
            "SELECT amount FROM account WHERE sync_id = ?", BigDecimal.class, account.getSyncId())));
    }

    @Test
    void restoresDeletedAccountWithSavedBalanceAndRejectsOldRestoreTokenAfterAnotherDeletion() throws Exception {
        UUID syncId = account.getSyncId();
        deleteFixture("/api/accounts/{id}", account.getAccount_id());
        UUID token = dependencyToken("ACCOUNT", syncId);
        String entry = offlineExpense(syncId, category.getSyncId());
        UUID command = UUID.randomUUID();

        sendOfflineExpense(command, entry).andExpect(status().isConflict())
            .andExpect(jsonPath("$.dependency.entityType").value("ACCOUNT"))
            .andExpect(jsonPath("$.dependency.needsCategoryDetails").value(false));

        String restore = """
            {"entityType":"ACCOUNT","syncId":"%s","restoreToken":"%s"}
            """.formatted(syncId, token);
        restoreDependency(restore).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(account.getAccount_id()))
            .andExpect(jsonPath("$.version").value(1));
        restoreDependency(restore).andExpect(status().isOk());
        assertEquals(0, new BigDecimal("100").compareTo(jdbcTemplate.queryForObject(
            "SELECT amount FROM account WHERE sync_id = ?", BigDecimal.class, syncId)));

        deleteFixture("/api/accounts/{id}", account.getAccount_id());
        restoreDependency(restore).andExpect(status().isConflict());
        UUID secondToken = dependencyToken("ACCOUNT", syncId);
        restoreDependency(restore.replace(token.toString(), secondToken.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        sendOfflineExpense(command, entry).andExpect(status().isCreated());
        assertEquals(0, new BigDecimal("70").compareTo(jdbcTemplate.queryForObject(
            "SELECT amount FROM account WHERE sync_id = ?", BigDecimal.class, syncId)));
    }

    private void deleteFixture(String path, Long id) throws Exception {
        mockMvc.perform(delete(path, id).with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().isNoContent());
        entryRepository.flush();
    }

    private UUID dependencyToken(String entityType, UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT restore_token FROM sync_tombstone WHERE user_id = ? AND entity_type = ? AND sync_id = ?",
            UUID.class, owner.getId(), entityType, id);
    }

    private String offlineExpense(UUID accountId, UUID categoryId) {
        return """
            {"syncId":"%s","title":"Offline expense","type":"EXPENSE","amount":30,
             "dateTime":"2026-10-19T12:00:00","accountSyncId":"%s","categorySyncId":"%s"}
            """.formatted(UUID.randomUUID(), accountId, categoryId);
    }

    private org.springframework.test.web.servlet.ResultActions sendOfflineExpense(UUID id, String entry)
            throws Exception {
        return mockMvc.perform(post("/api/sync/entries/{commandId}", id).with(user(owner.getUsername()))
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(entry));
    }

    private org.springframework.test.web.servlet.ResultActions restoreDependency(String request)
            throws Exception {
        return mockMvc.perform(post("/api/sync/dependencies/restore").with(user(owner.getUsername()))
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request));
    }

    @Test
    void repeatedExpenseReturnsSameResponseAndChargesOnlyOnce()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        long entriesBefore = entryRepository.count();
        long accountVersion = account.getVersion();

        String requestBody = """
            {
                "syncId": "%s",
                "title": "Offline lunch",
                "type": "EXPENSE",
                "amount": 25.00,
                "dateTime": "2026-10-19T12:00:00",
                "description": "Created on phone",
                "accountSyncId": "%s",
                "categorySyncId": "%s"
            }
            """.formatted(
                syncId,
                account.getSyncId(),
                category.getSyncId()
            );

        String firstResponse = mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andExpect(jsonPath("$.amount").value(25))
            .andExpect(jsonPath("$.accountVersionChanges.length()").value(1))
            .andExpect(jsonPath("$.accountVersionChanges[0].accountSyncId").value(account.getSyncId().toString()))
            .andExpect(jsonPath("$.accountVersionChanges[0].previousVersion").value(accountVersion))
            .andExpect(jsonPath("$.accountVersionChanges[0].version").value(accountVersion + 1))
            .andExpect(jsonPath("$.accountId")
                .value(account.getAccount_id().intValue()))
            .andExpect(jsonPath("$.categoryId")
                .value(category.getCategory_id().intValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        entryRepository.flush();

        assertEquals(entriesBefore + 1, entryRepository.count());
        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        assertEquals(entriesBefore + 1, entryRepository.count());
        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM entry
                WHERE sync_id = ?
                """,
                Long.class,
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

        mockMvc.perform(post("/api/sync/accounts/{commandId}/archive-state", UUID.randomUUID())
                .with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"syncId":"%s","expectedVersion":%d,"archived":true}
                    """.formatted(account.getSyncId(), accountVersion + 1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(accountVersion + 2))
            .andExpect(jsonPath("$.archivedAt").isNotEmpty());
    }

    @Test
    void rejectsChangedAmountForCompletedCommandWithoutChangingBalance()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        long entriesBefore = entryRepository.count();

        String requestTemplate = """
            {
                "syncId": "%s",
                "title": "Offline lunch",
                "type": "EXPENSE",
                "amount": %s,
                "dateTime": "2026-10-19T12:00:00",
                "description": "",
                "accountSyncId": "%s",
                "categorySyncId": "%s"
            }
            """;

        String originalRequest = requestTemplate.formatted(
            syncId,
            "25.00",
            account.getSyncId(),
            category.getSyncId()
        );

        mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(originalRequest)
            )
            .andExpect(status().isCreated());

        entryRepository.flush();

        String changedRequest = requestTemplate.formatted(
            syncId,
            "60.00",
            account.getSyncId(),
            category.getSyncId()
        );

        mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changedRequest)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Command ID is already used for a different request"
            ));

        assertEquals(entriesBefore + 1, entryRepository.count());

        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            0,
            new BigDecimal("25.00").compareTo(
                jdbcTemplate.queryForObject(
                    "SELECT amount FROM entry WHERE sync_id = ?",
                    BigDecimal.class,
                    syncId
                )
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
    void updatesEntryOnceAndRejectsStaleVersion() throws Exception {
        // The initial balance includes the original expense of 25.
        account.setAmount(new BigDecimal("75.00"));
        accountRepository.saveAndFlush(account);

        Entry original = entryRepository.saveAndFlush(
            new Entry(
                "Original lunch",
                EntryType.EXPENSE,
                new BigDecimal("25.00"),
                LocalDateTime.of(2026, 10, 19, 12, 0),
                "",
                category,
                account,
                owner
            )
        );

        UUID commandId = UUID.randomUUID();
        Long originalVersion = original.getVersion();
        long accountVersion = account.getVersion();
        long entriesBefore = entryRepository.count();

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d,
                "title": "Updated lunch",
                "type": "EXPENSE",
                "amount": 40.00,
                "dateTime": "2026-10-19T12:00:00",
                "description": "",
                "accountSyncId": "%s",
                "categorySyncId": "%s"
            }
            """.formatted(
                original.getSyncId(),
                originalVersion,
                account.getSyncId(),
                category.getSyncId()
            );

        String firstResponse = mockMvc.perform(
                put("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncId")
                .value(original.getSyncId().toString()))
            .andExpect(jsonPath("$.amount").value(40))
            .andExpect(jsonPath("$.accountVersionChanges.length()").value(1))
            .andExpect(jsonPath("$.accountVersionChanges[0].previousVersion").value(accountVersion))
            .andExpect(jsonPath("$.accountVersionChanges[0].version").value(accountVersion + 1))
            .andExpect(jsonPath("$.version")
                .value(originalVersion.longValue() + 1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // An exact retry returns the stored response.
        mockMvc.perform(
                put("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(content().string(firstResponse));

        // A new command with the old version must be rejected.
        UUID staleCommandId = UUID.randomUUID();

        mockMvc.perform(
                put("/api/sync/entries/{commandId}", staleCommandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Entry version conflict. Synchronize before retrying."
            ))
            .andExpect(jsonPath("$.code").value("ENTRY_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.serverEntry.syncId").value(original.getSyncId().toString()))
            .andExpect(jsonPath("$.serverEntry.version").value(originalVersion + 1))
            .andExpect(jsonPath("$.serverEntry.amount").value("40"))
            .andExpect(jsonPath("$.serverEntry.lastEditedBy.id").value(owner.getId()))
            .andExpect(jsonPath("$.serverEntry.lastEditedBy.username").value(owner.getUsername()));

        Entry updated = entryRepository.findById(original.getId())
            .orElseThrow();

        assertEquals("Updated lunch", updated.getTitle());
        assertEquals(
            originalVersion.longValue() + 1,
            updated.getVersion().longValue()
        );
        assertEquals(
            0,
            new BigDecimal("40.00").compareTo(updated.getAmount())
        );

        // 75 + 25 - 40 = 60.
        assertEquals(
            0,
            new BigDecimal("60.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(entriesBefore, entryRepository.count());

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
    void repeatedDeletionRestoresBalanceOnceAndRecordsTombstone()
            throws Exception {

        // The balance includes an existing expense of 25.
        account.setAmount(new BigDecimal("75.00"));
        accountRepository.saveAndFlush(account);

        Entry expense = entryRepository.saveAndFlush(
            new Entry(
                "Expense to delete",
                EntryType.EXPENSE,
                new BigDecimal("25.00"),
                LocalDateTime.of(2026, 10, 19, 12, 0),
                "",
                category,
                account,
                owner
            )
        );

        UUID syncId = expense.getSyncId();
        UUID commandId = UUID.randomUUID();

        long entriesBefore = entryRepository.count();
        long accountVersion = account.getVersion();

        String requestBody = """
            {
                "syncId": "%s",
                "expectedVersion": %d
            }
            """.formatted(syncId, expense.getVersion());

        String firstResponse = mockMvc.perform(
                post(
                    "/api/sync/entries/{commandId}/delete",
                    commandId
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andExpect(jsonPath("$.deleted").value(true))
            .andExpect(jsonPath("$.accountVersionChanges.length()").value(1))
            .andExpect(jsonPath("$.accountVersionChanges[0].previousVersion").value(accountVersion))
            .andExpect(jsonPath("$.accountVersionChanges[0].version").value(accountVersion + 1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                post(
                    "/api/sync/entries/{commandId}/delete",
                    commandId
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isOk())
            .andExpect(content().string(firstResponse));

        mockMvc.perform(post("/api/sync/entries/{commandId}/delete", UUID.randomUUID())
                .with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));


        assertEquals(entriesBefore - 1, entryRepository.count());

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entry WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'ENTRY'
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
    void rejectsDeletionWithStaleVersionWithoutChangingBalance()
            throws Exception {

        account.setAmount(new BigDecimal("75.00"));
        accountRepository.saveAndFlush(account);

        Entry expense = entryRepository.saveAndFlush(
            new Entry(
                "Original expense",
                EntryType.EXPENSE,
                new BigDecimal("25.00"),
                LocalDateTime.of(2026, 10, 19, 12, 0),
                "",
                category,
                account,
                owner
            )
        );

        Long staleVersion = expense.getVersion();
        UUID syncId = expense.getSyncId();
        UUID commandId = UUID.randomUUID();

        // Another device has changed the entry.
        expense.setTitle("Changed on another device");
        entryRepository.saveAndFlush(expense);

        Long currentVersion = expense.getVersion();
        long entriesBefore = entryRepository.count();

        assertEquals(
            staleVersion.longValue() + 1,
            currentVersion.longValue()
        );

        mockMvc.perform(
                post(
                    "/api/sync/entries/{commandId}/delete",
                    commandId
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "syncId": "%s",
                            "expectedVersion": %d
                        }
                        """.formatted(syncId, staleVersion))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Entry version conflict. Synchronize before retrying."
            ))
            .andExpect(jsonPath("$.code").value("ENTRY_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.serverEntry.id").value(expense.getId()));

        Entry unchanged = entryRepository.findById(expense.getId())
            .orElseThrow();

        assertEquals(
            "Changed on another device",
            unchanged.getTitle()
        );
        assertEquals(currentVersion, unchanged.getVersion());
        assertEquals(entriesBefore, entryRepository.count());

        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'ENTRY'
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

    @Test
    void ordinaryApiDeletionRecordsTombstoneAndRestoresBalance()
            throws Exception {

        account.setAmount(new BigDecimal("75.00"));
        accountRepository.saveAndFlush(account);

        Entry expense = entryRepository.saveAndFlush(
            new Entry(
                "Expense deleted through ordinary API",
                EntryType.EXPENSE,
                new BigDecimal("25.00"),
                LocalDateTime.of(2026, 10, 19, 12, 0),
                "",
                category,
                account,
                owner
            )
        );

        UUID syncId = expense.getSyncId();

        mockMvc.perform(
                delete("/api/entries/{id}", expense.getId())
                    .with(user(owner.getUsername()))
                    .with(csrf())
            )
            .andExpect(status().isNoContent());

        entryRepository.flush();

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entry WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'ENTRY'
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
                "SELECT COUNT(*) FROM sync_command WHERE user_id = ?",
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void rejectsRecreatingDeletedEntryWithSameSyncId()
            throws Exception {

        account.setAmount(new BigDecimal("75.00"));
        accountRepository.saveAndFlush(account);

        Entry expense = entryRepository.saveAndFlush(
            new Entry(
                "Deleted expense",
                EntryType.EXPENSE,
                new BigDecimal("25.00"),
                LocalDateTime.of(2026, 10, 19, 12, 0),
                "",
                category,
                account,
                owner
            )
        );

        UUID syncId = expense.getSyncId();

        mockMvc.perform(
                delete("/api/entries/{id}", expense.getId())
                    .with(user(owner.getUsername()))
                    .with(csrf())
            )
            .andExpect(status().isNoContent());

        entryRepository.flush();

        mockMvc.perform(
                put("/api/sync/entries/{commandId}", UUID.randomUUID())
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"syncId":"%s","expectedVersion":1,"title":"Edited expense","type":"EXPENSE",
                         "amount":30,"dateTime":"2026-10-19T12:00:00","accountSyncId":"%s","categorySyncId":"%s"}
                        """.formatted(syncId, account.getSyncId(), category.getSyncId()))
            )
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.message").value("The server deleted this operation and its previous version cannot be restored automatically"));

        UUID createCommandId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/entries/{commandId}", createCommandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "syncId": "%s",
                            "title": "Deleted expense",
                            "type": "EXPENSE",
                            "amount": 25.00,
                            "dateTime": "2026-10-19T12:00:00",
                            "description": "",
                            "accountSyncId": "%s",
                            "categorySyncId": "%s"
                        }
                        """.formatted(
                            syncId,
                            account.getSyncId(),
                            category.getSyncId()
                        ))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Entry was deleted and cannot be recreated with the same sync ID"
            ));

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entry WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
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
                createCommandId
            )
        );

        UUID updateCommand = UUID.randomUUID();
        String edit = """
            {"syncId":"%s","expectedVersion":0,"title":"Edited expense","type":"EXPENSE",
             "amount":30,"dateTime":"2026-10-19T12:00:00","accountSyncId":"%s","categorySyncId":"%s"}
            """.formatted(syncId, account.getSyncId(), category.getSyncId());
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/sync/entries/{commandId}", updateCommand)
                    .with(user(owner.getUsername())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncId").value(syncId.toString()))
                .andExpect(jsonPath("$.version").value(1));
        }
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT version FROM entry WHERE sync_id = ?", Long.class, syncId));
        assertEquals(0, new BigDecimal("70.00").compareTo(
            jdbcTemplate.queryForObject("SELECT amount FROM account WHERE account_id = ?", BigDecimal.class,
                account.getAccount_id())));
    }
}
