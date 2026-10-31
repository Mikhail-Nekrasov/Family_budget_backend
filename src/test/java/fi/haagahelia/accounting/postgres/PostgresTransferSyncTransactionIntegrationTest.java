package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

import fi.haagahelia.accounting.api.dto.SyncCreateTransferRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.TransferSyncService;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
class PostgresTransferSyncTransactionIntegrationTest {

    @Autowired
    private TransferSyncService transferSyncService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User owner;
    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        User accountOwner = new User();
        accountOwner.setUsername("transfer_sync_tx_" + UUID.randomUUID());
        accountOwner.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(accountOwner);

        source = accountRepository.saveAndFlush(
            new Account(
                "Source", new BigDecimal("100.00"), "", owner
            )
        );

        destination = accountRepository.saveAndFlush(
            new Account(
                "Destination", new BigDecimal("100.00"), "", owner
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
                    """
                    DELETE FROM transfer
                    WHERE from_account_id IN (
                        SELECT account_id FROM account WHERE user_id = ?
                    )
                    OR to_account_id IN (
                        SELECT account_id FROM account WHERE user_id = ?
                    )
                    """,
                    owner.getId(),
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
    void concurrentDuplicatesMoveMoneyOnceAndLaterRetryReturnsSameResult()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        LocalDateTime clientDate = LocalDateTime.of(
            2026, 10, 10, 12, 30
        );

        SyncCreateTransferRequest request =
            new SyncCreateTransferRequest(
                syncId,
                source.getSyncId(),
                destination.getSyncId(),
                new BigDecimal("75.00"),
                clientDate,
                "Concurrent transfer",
                null
            );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<CompletedCommand> task = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "Timed out waiting to start"
                    );
                }

                return transferSyncService.createTransfer(
                    commandId, request, owner
                );
            };

            var first = executor.submit(task);
            var second = executor.submit(task);

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

            // Both worker transactions have completed.
            CompletedCommand laterRetry =
                transferSyncService.createTransfer(
                    commandId, request, owner
                );

            assertEquals(firstResult, laterRetry);

            assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfer WHERE sync_id = ?",
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

            assertEquals(
                clientDate,
                transferRepository.findBySyncId(syncId)
                    .orElseThrow()
                    .getDateTime()
            );

            assertEquals(
                0,
                new BigDecimal("25.00").compareTo(
                    accountRepository.findById(source.getAccount_id())
                        .orElseThrow()
                        .getAmount()
                )
            );

            assertEquals(
                0,
                new BigDecimal("175.00").compareTo(
                    accountRepository.findById(
                        destination.getAccount_id()
                    ).orElseThrow().getAmount()
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