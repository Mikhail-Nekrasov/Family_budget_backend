package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresSyncIdIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void syncIdsSurvivePersistenceAndUpdates() {
        User user = new User();
        user.setUsername("sync_id_test_user");
        user.setPassword("unused");
        entityManager.persist(user);

        Account account = new Account(
            "First account", BigDecimal.valueOf(100),
            "Original description", user
        );

        Account destination = new Account(
            "Second account", BigDecimal.ZERO,
            "Destination", user
        );

        entityManager.persist(account);
        entityManager.persist(destination);

        Category category = new Category(
            "Test category", EntryType.EXPENSE, user
        );
        entityManager.persist(category);

        Entry entry = new Entry(
            "Test expense",
            EntryType.EXPENSE,
            BigDecimal.TEN,
            LocalDateTime.now(),
            "Original description",
            category,
            account,
            user
        );
        entityManager.persist(entry);

        Transfer transfer = new Transfer(
            account, destination, BigDecimal.ONE,
            "Original description"
        );
        entityManager.persist(transfer);

        UUID accountSyncId = account.getSyncId();
        UUID categorySyncId = category.getSyncId();
        UUID entrySyncId = entry.getSyncId();
        UUID transferSyncId = transfer.getSyncId();

        assertNotNull(accountSyncId);
        assertNotNull(categorySyncId);
        assertNotNull(entrySyncId);
        assertNotNull(transferSyncId);

        assertEquals(
            4,
            new HashSet<>(List.of(
                accountSyncId,
                categorySyncId,
                entrySyncId,
                transferSyncId
            )).size()
        );

        Long accountId = account.getAccount_id();
        Long categoryId = category.getCategory_id();
        Long entryId = entry.getId();
        Long transferId = transfer.getId();

        entityManager.flush();
        entityManager.clear();

        Account loadedAccount = entityManager.find(
            Account.class, accountId
        );
        Category loadedCategory = entityManager.find(
            Category.class, categoryId
        );
        Entry loadedEntry = entityManager.find(
            Entry.class, entryId
        );
        Transfer loadedTransfer = entityManager.find(
            Transfer.class, transferId
        );

        assertNotNull(loadedAccount);
        assertNotNull(loadedCategory);
        assertNotNull(loadedEntry);
        assertNotNull(loadedTransfer);

        assertEquals(accountSyncId, loadedAccount.getSyncId());
        assertEquals(categorySyncId, loadedCategory.getSyncId());
        assertEquals(entrySyncId, loadedEntry.getSyncId());
        assertEquals(transferSyncId, loadedTransfer.getSyncId());

        loadedAccount.setName("Changed account");
        loadedCategory.setName("Changed category");
        loadedEntry.setTitle("Changed expense");
        loadedTransfer.setDescription("Changed transfer");

        entityManager.flush();
        entityManager.clear();

        Account updatedAccount = entityManager.find(
            Account.class, accountId
        );
        Category updatedCategory = entityManager.find(
            Category.class, categoryId
        );
        Entry updatedEntry = entityManager.find(
            Entry.class, entryId
        );
        Transfer updatedTransfer = entityManager.find(
            Transfer.class, transferId
        );

        assertNotNull(updatedAccount);
        assertNotNull(updatedCategory);
        assertNotNull(updatedEntry);
        assertNotNull(updatedTransfer);

        assertEquals("Changed account", updatedAccount.getName());
        assertEquals("Changed category", updatedCategory.getName());
        assertEquals("Changed expense", updatedEntry.getTitle());
        assertEquals(
            "Changed transfer", updatedTransfer.getDescription()
        );

        assertEquals(accountSyncId, updatedAccount.getSyncId());
        assertEquals(categorySyncId, updatedCategory.getSyncId());
        assertEquals(entrySyncId, updatedEntry.getSyncId());
        assertEquals(transferSyncId, updatedTransfer.getSyncId());
    }
}