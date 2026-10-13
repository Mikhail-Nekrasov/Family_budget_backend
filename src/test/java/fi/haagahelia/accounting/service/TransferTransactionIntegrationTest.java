package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
public class TransferTransactionIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private TransferRepository transferRepository;

    private User owner;
    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("transfer_rollback_user");
        user.setPassword("unused-in-this-test");
        user.setRoles(Set.of());

        owner = userRepository.save(user);

        source = accountRepository.save(
            new Account(
                "Rollback source",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        destination = accountRepository.save(
            new Account(
                "Rollback destination",
                new BigDecimal("50.00"),
                "",
                owner
            )
        );
    }

    @AfterEach
    void cleanUp() {
        if (source != null) {
            accountRepository.deleteById(source.getAccount_id());
        }

        if (destination != null) {
            accountRepository.deleteById(destination.getAccount_id());
        }

        if (owner != null) {
            userRepository.deleteById(owner.getId());
        }
    }

    @Test
    void rollsBackBothBalancesWhenSavingTransferFails() {
        long transfersBefore = transferRepository.count();

        doAnswer(invocation -> {
            // Send the balance updates to the database before failing.
            accountRepository.flush();

            throw new IllegalStateException(
                "Simulated transfer save failure"
            );
        }).when(transferRepository).save(any(Transfer.class));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> transferService.executeTransfer(
                source.getAccount_id(),
                destination.getAccount_id(),
                new BigDecimal("25.00"),
                "Rollback test",
                owner
            )
        );

        assertEquals(
            "Simulated transfer save failure",
            exception.getMessage()
        );

        Account reloadedSource = accountRepository
            .findById(source.getAccount_id())
            .orElseThrow();

        Account reloadedDestination = accountRepository
            .findById(destination.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(reloadedSource.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("50.00").compareTo(reloadedDestination.getAmount())
        );
        assertEquals(transfersBefore, transferRepository.count());
    }
}