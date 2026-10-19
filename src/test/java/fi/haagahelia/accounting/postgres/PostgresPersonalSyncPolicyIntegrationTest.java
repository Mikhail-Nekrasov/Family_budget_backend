package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.*;
import fi.haagahelia.accounting.model.*;
import fi.haagahelia.accounting.repository.*;
import fi.haagahelia.accounting.service.*;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
class PostgresPersonalSyncPolicyIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private CategoryRepository categories;
    @Autowired private EntryRepository entries;
    @Autowired private EntrySyncService entrySync;
    @Autowired private AccountSyncService accountSync;
    @Autowired private TransferSyncService transferSync;
    @Autowired private SyncDownloadService downloads;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private User owner;
    private Account source;
    private Account target;
    private Category category;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("sync_policy_" + UUID.randomUUID());
        user.setPassword("unused");
        owner = users.saveAndFlush(user);
        source = accounts.saveAndFlush(new Account("Shared cash", new BigDecimal("100"), "", owner));
        target = accounts.saveAndFlush(new Account("Private cash", new BigDecimal("100"), "", owner));
        category = categories.saveAndFlush(new Category("Food", EntryType.EXPENSE, owner));
    }

    private void share(Account account) {
        Long groupId = jdbc.queryForObject(
            "INSERT INTO budget_group(name, owner_id, created_at) VALUES ('Trip', ?, LOCALTIMESTAMP) RETURNING id",
            Long.class, owner.getId());
        jdbc.update("INSERT INTO group_membership(group_id, user_id, joined_at) VALUES (?, ?, LOCALTIMESTAMP)", groupId, owner.getId());
        jdbc.update("INSERT INTO group_account_access(group_id, account_id, permission, created_at) VALUES (?, ?, 'EDIT', LOCALTIMESTAMP)", groupId, account.getAccount_id());
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM budget_group WHERE owner_id = ?", owner.getId());
            jdbc.update("DELETE FROM sync_command WHERE user_id = ?", owner.getId());
            jdbc.update("DELETE FROM sync_tombstone WHERE user_id = ?", owner.getId());
            jdbc.update("DELETE FROM transfer WHERE from_account_id IN (SELECT account_id FROM account WHERE user_id = ?)", owner.getId());
            jdbc.update("DELETE FROM entry WHERE user_id = ?", owner.getId());
            jdbc.update("DELETE FROM category WHERE user_id = ?", owner.getId());
            jdbc.update("DELETE FROM account WHERE user_id = ?", owner.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", owner.getId());
        });
    }

    private SyncCreateEntryRequest expense(UUID id, Account account) {
        return new SyncCreateEntryRequest(id, "Lunch", EntryType.EXPENSE,
            new BigDecimal("25"), LocalDateTime.of(2026, 10, 19, 12, 0), "",
            account.getSyncId(), category.getSyncId());
    }

    private void conflict(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("Shared accounts require the online API; queued personal commands are not allowed", error.getReason());
    }

    @Test
    void queuedCommandsCannotChangeSharedAccountOrCreateExpenseOrTransfer() {
        share(source);
        conflict(() -> accountSync.updateAccount(UUID.randomUUID(),
            new SyncUpdateAccountRequest(source.getSyncId(), source.getVersion(), "Changed", ""), owner));
        conflict(() -> accountSync.deleteAccount(UUID.randomUUID(),
            new SyncDeleteAccountRequest(source.getSyncId(), source.getVersion()), owner));
        conflict(() -> entrySync.createEntry(UUID.randomUUID(), expense(UUID.randomUUID(), source), owner));
        conflict(() -> transferSync.createTransfer(UUID.randomUUID(), new SyncCreateTransferRequest(
            UUID.randomUUID(), source.getSyncId(), target.getSyncId(), BigDecimal.ONE,
            LocalDateTime.of(2026, 10, 19, 12, 0), ""), owner));
        // Shared destination is blocked too, even though both accounts belong to this user.
        conflict(() -> transferSync.createTransfer(UUID.randomUUID(), new SyncCreateTransferRequest(
            UUID.randomUUID(), target.getSyncId(), source.getSyncId(), BigDecimal.ONE,
            LocalDateTime.of(2026, 10, 19, 12, 0), ""), owner));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM sync_command WHERE user_id = ?", Long.class, owner.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM entry WHERE user_id = ?", Long.class, owner.getId()));
        assertEquals(0, new BigDecimal("100").compareTo(accounts.findById(source.getAccount_id()).orElseThrow().getAmount()));
    }

    @Test
    void completedCommandStillReplaysAfterAccountIsSharedButNewEditsAreBlocked() {
        UUID syncId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var request = expense(syncId, source);
        var response = entrySync.createEntry(commandId, request, owner);
        var saved = entries.findOwnedBySyncId(syncId, owner).orElseThrow();
        share(source);
        assertEquals(response, entrySync.createEntry(commandId, request, owner));
        conflict(() -> entrySync.updateEntry(UUID.randomUUID(), new SyncUpdateEntryRequest(
            syncId, saved.getVersion(), "Changed", EntryType.EXPENSE, BigDecimal.ONE,
            request.dateTime(), "", target.getSyncId(), category.getSyncId()), owner));
        conflict(() -> entrySync.deleteEntry(UUID.randomUUID(),
            new SyncDeleteEntryRequest(syncId, saved.getVersion()), owner));
        assertEquals(0, new BigDecimal("75").compareTo(accounts.findById(source.getAccount_id()).orElseThrow().getAmount()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM sync_command WHERE user_id = ?", Long.class, owner.getId()));
    }

    @Test
    void privateEntryCannotBeMovedToSharedAccountAndSharingFlagAppearsInDelta() {
        UUID syncId = UUID.randomUUID();
        var request = expense(syncId, target);
        entrySync.createEntry(UUID.randomUUID(), request, owner);
        var saved = entries.findOwnedBySyncId(syncId, owner).orElseThrow();
        UUID baseline = downloads.start(null, owner).downloadId();
        share(source);
        conflict(() -> entrySync.updateEntry(UUID.randomUUID(), new SyncUpdateEntryRequest(
            syncId, saved.getVersion(), "Lunch", EntryType.EXPENSE, new BigDecimal("25"),
            request.dateTime(), "", source.getSyncId(), category.getSyncId()), owner));
        var page = downloads.page(downloads.start(baseline, owner).downloadId(), 0, owner);
        var changed = page.items().stream().filter(item -> item.syncId().equals(source.getSyncId())).findFirst().orElseThrow();
        assertTrue(changed.data().get("shared").booleanValue());
        jdbc.update("DELETE FROM group_account_access WHERE account_id = ?", source.getAccount_id());
        var next = downloads.page(downloads.start(page.nextCursor(), owner).downloadId(), 0, owner);
        assertFalse(next.items().stream().filter(item -> item.syncId().equals(source.getSyncId())).findFirst().orElseThrow().data().get("shared").booleanValue());
    }
}
