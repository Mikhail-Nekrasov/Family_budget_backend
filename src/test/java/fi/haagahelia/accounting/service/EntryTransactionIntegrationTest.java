package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
@ActiveProfiles("test")
class EntryTransactionIntegrationTest {

    @Autowired
    private EntryService entryService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private EntryRepository entryRepository;

    private User owner;
    private Account account;
    private Category category;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("entry_rollback_user");
        user.setPassword("unused-in-this-test");
        user.setRoles(Set.of());

        owner = userRepository.save(user);

        account = accountRepository.save(
            new Account(
                "Rollback account",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        category = categoryRepository.save(
            new Category("Rollback food", EntryType.EXPENSE, owner)
        );
    }

    @AfterEach
    void cleanUp() {
        if (account != null) {
            accountRepository.deleteById(account.getAccount_id());
        }

        if (category != null) {
            categoryRepository.deleteById(category.getCategory_id());
        }

        if (owner != null) {
            userRepository.deleteById(owner.getId());
        }
    }

    @Test
    void rollsBackBalanceWhenSavingEntryFails() {
        long entriesBefore = entryRepository.count();

        Entry entry = new Entry(
            "Lunch",
            EntryType.EXPENSE,
            new BigDecimal("25.00"),
            LocalDateTime.of(2026, 10, 12, 12, 0),
            "Rollback test",
            category,
            account,
            owner
        );

        doAnswer(invocation -> {
            accountRepository.flush();

            throw new IllegalStateException(
                "Simulated entry save failure"
            );
        }).when(entryRepository).save(any(Entry.class));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> entryService.createEntry(entry, owner)
        );

        assertEquals(
            "Simulated entry save failure",
            exception.getMessage()
        );

        Account reloaded = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(reloaded.getAmount())
        );
        assertEquals(entriesBefore, entryRepository.count());
    }
}