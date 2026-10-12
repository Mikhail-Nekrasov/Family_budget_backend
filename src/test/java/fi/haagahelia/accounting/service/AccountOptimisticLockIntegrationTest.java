package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class AccountOptimisticLockIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private Account account;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("optimistic_lock_user");
        user.setPassword("unused-in-this-test");
        user.setRoles(Set.of());

        owner = userRepository.save(user);

        account = accountRepository.save(
            new Account(
                "Versioned account",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );
    }

    @AfterEach
    void cleanUp() {
        if (account != null) {
            accountRepository.deleteById(account.getAccount_id());
        }

        if (owner != null) {
            userRepository.deleteById(owner.getId());
        }
    }

    @Test
    void rejectsSavingStaleAccountVersion() {
        Long accountId = account.getAccount_id();

        Account firstCopy = accountRepository
            .findById(accountId)
            .orElseThrow();

        Account secondCopy = accountRepository
            .findById(accountId)
            .orElseThrow();

        assertNotSame(firstCopy, secondCopy);
        assertNotNull(firstCopy.getVersion());
        assertEquals(firstCopy.getVersion(), secondCopy.getVersion());

        Long originalVersion = firstCopy.getVersion();

        firstCopy.setAmount(new BigDecimal("90.00"));
        accountRepository.saveAndFlush(firstCopy);

        secondCopy.setAmount(new BigDecimal("80.00"));

        assertThrows(
            OptimisticLockingFailureException.class,
            () -> accountRepository.saveAndFlush(secondCopy)
        );

        Account reloaded = accountRepository
            .findById(accountId)
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("90.00").compareTo(reloaded.getAmount())
        );
        assertTrue(reloaded.getVersion() > originalVersion);
    }
}