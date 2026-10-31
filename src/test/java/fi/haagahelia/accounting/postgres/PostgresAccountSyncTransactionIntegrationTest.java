package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountSyncService;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
class PostgresAccountSyncTransactionIntegrationTest {

    @Autowired
    private AccountSyncService accountSyncService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SyncCommandRepository syncCommandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User owner;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("sync_tx_" + UUID.randomUUID());
        user.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(user);
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
                    "DELETE FROM account WHERE user_id = ?",
                    owner.getId()
                );

                userRepository.deleteById(owner.getId());
            });
    }

    @Test
    void repeatsCommandAfterFirstTransactionHasCommitted() {
        UUID commandId = UUID.randomUUID();

        CreateAccountRequest request = new CreateAccountRequest(
            "Committed account",
            new BigDecimal("100.00"),
            "",
            UUID.randomUUID(),
            null,
            null
        );

        // The service opens and commits its own transaction.
        CompletedCommand first = accountSyncService.createAccount(
            commandId, request, owner
        );

        assertEquals(
            first,
            syncCommandRepository
                .findCompleted(owner.getId(), commandId)
                .orElseThrow()
        );
        assertEquals(
            1,
            accountRepository.findByUser(owner).size()
        );

        // This call uses a new transaction.
        CompletedCommand repeated = accountSyncService.createAccount(
            commandId, request, owner
        );

        assertEquals(first, repeated);
        assertEquals(
            1,
            accountRepository.findByUser(owner).size()
        );
    }

    @Test
    void rollsBackAccountAndCommandResultTogether() {
        UUID commandId = UUID.randomUUID();

        CreateAccountRequest request = new CreateAccountRequest(
            "Rolled back account",
            BigDecimal.ZERO,
            "",
            UUID.randomUUID(),
            null,
            null
        );

        TransactionTemplate transaction =
            new TransactionTemplate(transactionManager);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> transaction.executeWithoutResult(status -> {
                accountSyncService.createAccount(
                    commandId, request, owner
                );

                // Ensure pending JPA changes reach PostgreSQL.
                accountRepository.flush();

                throw new IllegalStateException(
                    "Simulated transaction failure"
                );
            })
        );

        assertEquals(
            "Simulated transaction failure",
            failure.getMessage()
        );

        assertEquals(
            0,
            accountRepository.findByUser(owner).size()
        );
        assertEquals(
            true,
            syncCommandRepository
                .findCompleted(owner.getId(), commandId)
                .isEmpty()
        );

        // A rolled-back command can be retried successfully.
        CompletedCommand result = accountSyncService.createAccount(
            commandId, request, owner
        );

        assertEquals(201, result.responseStatus());
        assertEquals(
            1,
            accountRepository.findByUser(owner).size()
        );
        assertEquals(
            result,
            syncCommandRepository
                .findCompleted(owner.getId(), commandId)
                .orElseThrow()
        );
    }

    @Test
    void concurrentDuplicateCommandsCreateOnlyOneAccount()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        CreateAccountRequest request = new CreateAccountRequest(
            "Concurrent account",
            new BigDecimal("100.00"),
            "",
            syncId,
            null,
            null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<CompletedCommand> first = executor.submit(() -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "Timed out waiting to start"
                    );
                }

                return accountSyncService.createAccount(
                    commandId, request, owner
                );
            });

            Future<CompletedCommand> second = executor.submit(() -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "Timed out waiting to start"
                    );
                }

                return accountSyncService.createAccount(
                    commandId, request, owner
                );
            });

            assertTrue(
                ready.await(10, TimeUnit.SECONDS),
                "Both workers must be ready"
            );

            start.countDown();

            CompletedCommand firstResult = first.get(
                30, TimeUnit.SECONDS
            );
            CompletedCommand secondResult = second.get(
                30, TimeUnit.SECONDS
            );

            assertEquals(201, firstResult.responseStatus());
            assertEquals(firstResult, secondResult);

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
                    WHERE user_id = ? AND command_id = ?
                    """,
                    Long.class,
                    owner.getId(),
                    commandId
                )
            );
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                executor.awaitTermination(30, TimeUnit.SECONDS),
                "Workers must stop before test cleanup"
            );
        }
    }
}