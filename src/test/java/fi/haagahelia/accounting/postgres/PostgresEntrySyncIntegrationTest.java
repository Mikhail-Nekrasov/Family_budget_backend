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
    void repeatedExpenseReturnsSameResponseAndChargesOnlyOnce()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        long entriesBefore = entryRepository.count();

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
            ));

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
            ));

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
    }
}