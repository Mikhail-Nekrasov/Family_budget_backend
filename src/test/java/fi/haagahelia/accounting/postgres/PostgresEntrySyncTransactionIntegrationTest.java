package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.SyncCreateEntryRequest;
import fi.haagahelia.accounting.api.dto.SyncUpdateEntryRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.EntrySyncService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
class PostgresEntrySyncTransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntrySyncService entrySyncService;

    @Autowired
    private EntryRepository entryRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User owner;
    private Account account;

    @BeforeEach
    void setUp() {
        User accountOwner = new User();
        accountOwner.setUsername("entry_sync_tx_" + UUID.randomUUID());
        accountOwner.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(accountOwner);

        account = accountRepository.saveAndFlush(
            new Account(
                "Sync transaction account",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> {
                jdbcTemplate.update(
                    "DELETE FROM sync_command WHERE user_id = ?",
                    owner.getId()
                );

                jdbcTemplate.update(
                    "DELETE FROM entry WHERE user_id = ?",
                    owner.getId()
                );

                jdbcTemplate.update(
                    "DELETE FROM category WHERE user_id = ?",
                    owner.getId()
                );

                jdbcTemplate.update(
                    "DELETE FROM account WHERE user_id = ?",
                    owner.getId()
                );

                userRepository.deleteById(owner.getId());
            });
    }

    @Test
    void retriesSuccessfullyAfterMissingCategoryIsSynchronized()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID entrySyncId = UUID.randomUUID();
        UUID categorySyncId = UUID.randomUUID();

        String requestBody = """
            {
                "syncId": "%s",
                "title": "Expense waiting for category",
                "type": "EXPENSE",
                "amount": 25.00,
                "dateTime": "2026-10-19T12:00:00",
                "description": "",
                "accountSyncId": "%s",
                "categorySyncId": "%s"
            }
            """.formatted(
                entrySyncId,
                account.getSyncId(),
                categorySyncId
            );

        // The category has not reached the server yet.
        mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "Category is unavailable for synchronization"
            ));

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entry WHERE sync_id = ?",
                Long.class,
                entrySyncId
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

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        // Synchronize the dependency using a separate command.
        mockMvc.perform(
                post(
                    "/api/sync/categories/{commandId}",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "New offline category",
                            "type": "EXPENSE",
                            "syncId": "%s"
                        }
                        """.formatted(categorySyncId))
            )
            .andExpect(status().isCreated());

        // Retry the original command with exactly the same payload.
        String successfulResponse = mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncId")
                .value(entrySyncId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // A further retry must return the stored result.
        mockMvc.perform(
                post("/api/sync/entries/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(successfulResponse));

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entry WHERE sync_id = ?",
                Long.class,
                entrySyncId
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

        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );
    }

    @Test
    void staleUpdateCanBeRetriedAfterLoadingCurrentVersion() {
        Category category = categoryRepository.saveAndFlush(
            new Category("Sync food", EntryType.EXPENSE, owner)
        );

        UUID entrySyncId = UUID.randomUUID();

        LocalDateTime dateTime = LocalDateTime.of(
            2026, 10, 19, 12, 0
        );

        entrySyncService.createEntry(
            UUID.randomUUID(),
            new SyncCreateEntryRequest(
                entrySyncId,
                "Lunch",
                EntryType.EXPENSE,
                new BigDecimal("25.00"),
                dateTime,
                "",
                account.getSyncId(),
                category.getSyncId()
            ),
            owner
        );

        Long initialVersion = entryRepository
            .findOwnedBySyncId(entrySyncId, owner)
            .orElseThrow()
            .getVersion();

        // Device A updates the expense from 25 to 40.
        UUID firstUpdateCommandId = UUID.randomUUID();

        SyncUpdateEntryRequest firstUpdate = new SyncUpdateEntryRequest(
            entrySyncId,
            initialVersion,
            "Lunch",
            EntryType.EXPENSE,
            new BigDecimal("40.00"),
            dateTime,
            "",
            account.getSyncId(),
            category.getSyncId()
        );

        var firstResult = entrySyncService.updateEntry(
            firstUpdateCommandId, firstUpdate, owner
        );

        // Device B still has the original version.
        UUID secondUpdateCommandId = UUID.randomUUID();

        SyncUpdateEntryRequest staleUpdate = new SyncUpdateEntryRequest(
            entrySyncId,
            initialVersion,
            "Lunch",
            EntryType.EXPENSE,
            new BigDecimal("60.00"),
            dateTime,
            "",
            account.getSyncId(),
            category.getSyncId()
        );

        ResponseStatusException failure = assertThrows(
            ResponseStatusException.class,
            () -> entrySyncService.updateEntry(
                secondUpdateCommandId, staleUpdate, owner
            )
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());

        var current = entryRepository
            .findOwnedBySyncId(entrySyncId, owner)
            .orElseThrow();

        assertEquals(
            initialVersion.longValue() + 1,
            current.getVersion().longValue()
        );
        assertEquals(
            0,
            new BigDecimal("40.00").compareTo(current.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("60.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        // The failed command has no completed result.
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
                secondUpdateCommandId
            )
        );

        // After reviewing current data, device B submits a resolved update.
        SyncUpdateEntryRequest resolvedUpdate =
            new SyncUpdateEntryRequest(
                entrySyncId,
                current.getVersion(),
                "Lunch",
                EntryType.EXPENSE,
                new BigDecimal("60.00"),
                dateTime,
                "",
                account.getSyncId(),
                category.getSyncId()
            );

        var resolvedResult = entrySyncService.updateEntry(
            secondUpdateCommandId, resolvedUpdate, owner
        );

        assertEquals(200, resolvedResult.responseStatus());

        var finalEntry = entryRepository
            .findOwnedBySyncId(entrySyncId, owner)
            .orElseThrow();

        assertEquals(
            initialVersion.longValue() + 2,
            finalEntry.getVersion().longValue()
        );
        assertEquals(
            0,
            new BigDecimal("60.00").compareTo(finalEntry.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("40.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        // Retrying device A's completed command returns its original result.
        assertEquals(
            firstResult,
            entrySyncService.updateEntry(
                firstUpdateCommandId, firstUpdate, owner
            )
        );

        assertEquals(
            0,
            new BigDecimal("40.00").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );
    }
}