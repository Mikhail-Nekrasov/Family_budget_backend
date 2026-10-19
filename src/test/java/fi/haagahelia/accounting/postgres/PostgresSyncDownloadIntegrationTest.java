package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

import fi.haagahelia.accounting.api.dto.SyncDownloadItem;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.*;
import fi.haagahelia.accounting.service.SyncDownloadService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
class PostgresSyncDownloadIntegrationTest {
    @Autowired private SyncDownloadService downloads;
    @Autowired private SyncDownloadRepository downloadRepository;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private CategoryRepository categories;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;

    private User owner;
    private User other;
    private Account account;
    private Category category;

    @BeforeEach
    void setUp() {
        owner = createUser();
        other = createUser();
        account = accounts.saveAndFlush(new Account("Cash", new BigDecimal("100"), "", owner));
        category = categories.saveAndFlush(new Category("Food", EntryType.EXPENSE, owner));
        accounts.saveAndFlush(new Account("Foreign private account", new BigDecimal("999"), "secret", other));
    }

    private User createUser() {
        User user = new User();
        user.setUsername("download_test_" + UUID.randomUUID());
        user.setPassword("unused");
        return users.saveAndFlush(user);
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM transfer WHERE from_account_id IN (SELECT account_id FROM account WHERE user_id IN (?, ?)) OR to_account_id IN (SELECT account_id FROM account WHERE user_id IN (?, ?))",
                owner.getId(), other.getId(), owner.getId(), other.getId());
            jdbc.update("DELETE FROM entry WHERE user_id IN (?, ?)", owner.getId(), other.getId());
            jdbc.update("DELETE FROM category WHERE user_id IN (?, ?)", owner.getId(), other.getId());
            jdbc.update("DELETE FROM account WHERE user_id IN (?, ?)", owner.getId(), other.getId());
            jdbc.update("DELETE FROM sync_command WHERE user_id IN (?, ?)", owner.getId(), other.getId());
            jdbc.update("DELETE FROM sync_tombstone WHERE user_id IN (?, ?)", owner.getId(), other.getId());
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", owner.getId(), other.getId());
        });
    }

    private List<SyncDownloadItem> all(UUID id) {
        List<SyncDownloadItem> result = new ArrayList<>();
        long after = 0;
        do {
            var page = downloads.page(id, after, owner);
            result.addAll(page.items());
            if (page.nextAfter() == null) {
                assertEquals(id, page.nextCursor());
                return result;
            }
            assertNull(page.nextCursor());
            after = page.nextAfter();
        } while (true);
    }

    private UUID insertEntry() {
        return jdbc.queryForObject("""
            INSERT INTO entry(title, type, amount, date_time, description, category_id, account_id, user_id)
            VALUES ('Lunch', 'EXPENSE', 1.25, LOCALTIMESTAMP, '', ?, ?, ?) RETURNING sync_id
            """, UUID.class, category.getCategory_id(), account.getAccount_id(), owner.getId());
    }

    private SyncDownloadItem find(List<SyncDownloadItem> items, UUID syncId) {
        return items.stream().filter(item -> item.syncId().equals(syncId)).findFirst().orElseThrow();
    }

    @Test
    void fullDownloadIncludesOwnDataAndUuidReferencesWithoutCredentialsOrForeignBalances() {
        UUID entryId = insertEntry();
        var manifest = downloads.start(null, owner);
        assertEquals("FULL", manifest.mode());
        var items = all(manifest.downloadId());
        assertEquals(3, items.size());
        assertEquals(account.getSyncId().toString(), find(items, entryId).data().get("accountSyncId").stringValue());
        assertEquals(category.getSyncId().toString(), find(items, entryId).data().get("categorySyncId").stringValue());
        assertFalse(items.toString().contains("password"));
        assertFalse(items.toString().contains("Foreign private account"));
        assertFalse(find(items, account.getSyncId()).data().get("shared").booleanValue());
        assertFalse(find(items, category.getSyncId()).data().get("readOnly").booleanValue());
    }

    @Test
    void pagesRemainStableWhenLiveRowsAreUpdatedAndDeleted() {
        jdbc.update("""
            INSERT INTO entry(title, type, amount, date_time, description, category_id, account_id, user_id)
            SELECT 'Expense ' || n, 'EXPENSE', 1, LOCALTIMESTAMP, '', ?, ?, ?
            FROM generate_series(1, 205) n
            """, category.getCategory_id(), account.getAccount_id(), owner.getId());
        var manifest = downloads.start(null, owner);
        var first = downloads.page(manifest.downloadId(), 0, owner);
        assertEquals(200, first.items().size());
        assertNotNull(first.nextAfter());
        jdbc.update("UPDATE entry SET title = 'Changed later', version = version + 1 WHERE user_id = ?", owner.getId());
        jdbc.update("DELETE FROM entry WHERE user_id = ?", owner.getId());
        var items = all(manifest.downloadId());
        assertEquals(207, items.size());
        assertEquals(207, items.stream().map(item -> item.syncId()).distinct().count());
        assertFalse(items.toString().contains("Changed later"));
        assertEquals(first, downloads.page(manifest.downloadId(), 0, owner));
        var delta = all(downloads.start(manifest.downloadId(), owner).downloadId());
        assertEquals(205, delta.stream().filter(item -> item.entityType().equals("ENTRY") && item.action().equals("DELETE")).count());
    }

    @Test
    void deltaIncludesOrdinarySqlUpdatesAndDeletesAndNextDeltaIsEmpty() {
        UUID entryId = insertEntry();
        UUID baseline = downloads.start(null, owner).downloadId();
        jdbc.update("UPDATE account SET name = 'Changed cash', version = version + 1 WHERE account_id = ?", account.getAccount_id());
        jdbc.update("DELETE FROM entry WHERE sync_id = ?", entryId);
        var delta = downloads.start(baseline, owner);
        assertEquals("DELTA", delta.mode());
        var items = all(delta.downloadId());
        assertEquals("Changed cash", find(items, account.getSyncId()).data().get("name").stringValue());
        assertEquals("DELETE", find(items, entryId).action());
        assertTrue(find(items, entryId).data().isNull());
        assertTrue(all(downloads.start(delta.downloadId(), owner).downloadId()).isEmpty());
    }

    @Test
    void rollbackProducesNoChanges() {
        UUID baseline = downloads.start(null, owner).downloadId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE account SET name = 'Rolled back' WHERE account_id = ?", account.getAccount_id());
            status.setRollbackOnly();
        });
        assertTrue(all(downloads.start(baseline, owner).downloadId()).isEmpty());
    }

    @Test
    void transactionThatCommitsLateIsNotSkippedByCursor() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            var future = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE account SET name = 'Committed late', version = version + 1 WHERE account_id = ?", account.getAccount_id());
                written.countDown();
                try {
                    if (!commit.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Commit gate timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(written.await(10, TimeUnit.SECONDS));
            // This commit has a higher journal sequence, but happens first.
            jdbc.update("UPDATE category SET name = 'Committed early', version = version + 1 WHERE category_id = ?", category.getCategory_id());
            UUID baseline = downloads.start(null, owner).downloadId();
            assertEquals("Cash", find(all(baseline), account.getSyncId()).data().get("name").stringValue());
            commit.countDown();
            future.get(20, TimeUnit.SECONDS);
            var items = all(downloads.start(baseline, owner).downloadId());
            assertEquals("Committed late", find(items, account.getSyncId()).data().get("name").stringValue());
            assertFalse(items.stream().anyMatch(item -> item.syncId().equals(category.getSyncId())));
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void foreignUserCannotReadUseOrCompleteDownload() {
        UUID id = downloads.start(null, owner).downloadId();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> downloads.page(id, 0, other)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> downloads.start(id, other)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> downloads.complete(id, other)).getStatusCode());
        assertEquals(2, all(id).size());
    }

    @Test
    void completingDownloadReleasesPagesButPreservesCursor() {
        UUID id = downloads.start(null, owner).downloadId();
        all(id);
        downloads.complete(id, owner);
        downloads.complete(id, owner);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM sync_download_item WHERE download_id = ?", Long.class, id));
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class,
            () -> downloads.page(id, 0, owner)).getStatusCode());
        assertTrue(all(downloads.start(id, owner).downloadId()).isEmpty());
    }

    @Test
    void expiredPagesAndCursorRequireRestartAndInvalidPositionIsRejected() {
        UUID id = downloads.start(null, owner).downloadId();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> downloads.page(id, -1, owner)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> downloads.page(id, 999, owner)).getStatusCode());
        jdbc.update("UPDATE sync_download SET pages_expire_at = clock_timestamp() - INTERVAL '1 second', cursor_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", id);
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class,
            () -> downloads.page(id, 0, owner)).getStatusCode());
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class,
            () -> downloads.start(id, owner)).getStatusCode());
        assertEquals("FULL", downloads.start(null, owner).mode());
    }

    @Test
    void httpFlowRequiresAuthenticationAndCsrf() throws Exception {
        mockMvc.perform(post("/api/sync/downloads").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sync/downloads").with(user(owner.getUsername()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/sync/downloads").with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.mode").value("FULL"));
        UUID id = downloads.start(null, owner).downloadId();
        mockMvc.perform(get("/api/sync/downloads/{id}", id).with(user(owner.getUsername())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value(id.toString()));
        mockMvc.perform(get("/api/sync/downloads/{id}", id).with(user(other.getUsername())))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sync/downloads/{id}/complete", id).with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void cleanupKeepsOldChangesNotYetVisibleToActiveCursor() {
        UUID baseline = downloads.start(null, owner).downloadId();
        jdbc.update("UPDATE account SET name = 'Keep this delta', version = version + 1 WHERE account_id = ?", account.getAccount_id());
        // Artificially age the event while retaining a valid cursor that has not seen it.
        jdbc.update("UPDATE sync_change SET occurred_at = clock_timestamp() - INTERVAL '31 days' WHERE user_id = ?", owner.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> downloadRepository.cleanup());
        assertEquals("Keep this delta", find(all(downloads.start(baseline, owner).downloadId()), account.getSyncId()).data().get("name").stringValue());
    }
    @Test
    void referencedForeignCategoryIsReadOnlyAndLeavesDatasetWhenLastReferenceIsRemoved() {
        Category foreign = categories.saveAndFlush(new Category("Shared label", EntryType.EXPENSE, other));
        UUID baseline = downloads.start(null, owner).downloadId();
        UUID entryId = jdbc.queryForObject("""
            INSERT INTO entry(title, type, amount, date_time, category_id, account_id, user_id)
            VALUES ('Shared expense', 'EXPENSE', 1, LOCALTIMESTAMP, ?, ?, ?) RETURNING sync_id
            """, UUID.class, foreign.getCategory_id(), account.getAccount_id(), owner.getId());
        UUID next = downloads.start(baseline, owner).downloadId();
        var items = all(next);
        assertTrue(find(items, foreign.getSyncId()).data().get("readOnly").booleanValue());
        assertEquals("UPSERT", find(items, entryId).action());
        jdbc.update("UPDATE category SET name = 'Renamed label', version = version + 1 WHERE category_id = ?", foreign.getCategory_id());
        UUID renamed = downloads.start(next, owner).downloadId();
        assertEquals("Renamed label", find(all(renamed), foreign.getSyncId()).data().get("name").stringValue());
        jdbc.update("DELETE FROM entry WHERE sync_id = ?", entryId);
        items = all(downloads.start(renamed, owner).downloadId());
        assertEquals("DELETE", find(items, foreign.getSyncId()).action());
        assertEquals("DELETE", find(items, entryId).action());
    }

    @Test
    void crossUserTransferIncludesLabelsButNotCounterpartyBalanceAndRenameIsPublished() {
        var foreign = accounts.findByUser(other).get(0);
        UUID transferId = jdbc.queryForObject("""
            INSERT INTO transfer(from_account_id, to_account_id, amount, date_time, description)
            VALUES (?, ?, 1, LOCALTIMESTAMP, '') RETURNING sync_id
            """, UUID.class, account.getAccount_id(), foreign.getAccount_id());
        UUID baseline = downloads.start(null, owner).downloadId();
        var items = all(baseline);
        var transfer = find(items, transferId).data();
        assertTrue(transfer.get("fromAccountOwned").booleanValue());
        assertFalse(transfer.get("toAccountOwned").booleanValue());
        assertFalse(items.stream().anyMatch(item -> item.syncId().equals(foreign.getSyncId())));
        assertEquals("Foreign private account", transfer.get("toAccountName").stringValue());
        jdbc.update("UPDATE account SET name = 'New counterparty label', version = version + 1 WHERE account_id = ?", foreign.getAccount_id());
        items = all(downloads.start(baseline, owner).downloadId());
        assertEquals("New counterparty label", find(items, transferId).data().get("toAccountName").stringValue());
        assertFalse(items.stream().anyMatch(item -> item.syncId().equals(foreign.getSyncId())));
    }

}
