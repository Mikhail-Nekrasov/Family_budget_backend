package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.UpdateAccountOrderRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository;
import fi.haagahelia.accounting.repository.UserAccountOrderRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;
import fi.haagahelia.accounting.service.UserAccountOrderService;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
class PostgresAccountOrderIntegrationTest {

    @Autowired
    private UserAccountOrderService service;

    @Autowired
    private UserAccountOrderRepository orderRepository;

    @Autowired
    private SyncCommandRepository commandRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccountService accountService;

    private User owner;
    private Account firstAccount;
    private Account secondAccount;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("account_order_" + UUID.randomUUID());
        user.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(user);

        firstAccount = accountRepository.saveAndFlush(
            new Account("First", BigDecimal.ZERO, null, owner)
        );

        secondAccount = accountRepository.saveAndFlush(
            new Account("Second", BigDecimal.ZERO, null, owner)
        );
    }

    @AfterEach
    void cleanUp() {
        if (owner == null) {
            return;
        }

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> {
                jdbcTemplate.update(
                    "DELETE FROM sync_command WHERE user_id = ?",
                    owner.getId()
                );

                jdbcTemplate.update(
                    "DELETE FROM user_account_order WHERE user_id = ?",
                    owner.getId()
                );

                jdbcTemplate.update(
                    "DELETE FROM account WHERE user_id = ?",
                    owner.getId()
                );

                jdbcTemplate.update(
                    "DELETE FROM sync_tombstone WHERE user_id = ?",
                    owner.getId()
                );

                userRepository.deleteById(owner.getId());
            });
    }

    private int submitConcurrentOrder(
            UpdateAccountOrderRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "Timed out waiting to start"
            );
        }

        try {
            return service.saveOrder(
                UUID.randomUUID(),
                request,
                owner
            ).responseStatus();
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    @Test
    void storesOrderAndDoesNotApplyRepeatedCommandTwice() {
        UUID commandId = UUID.randomUUID();

        var request = new UpdateAccountOrderRequest(
            List.of(
                secondAccount.getSyncId(),
                firstAccount.getSyncId()
            ),
            0L
        );

        var firstResult = service.saveOrder(
            commandId, request, owner
        );

        assertEquals(
            List.of(
                secondAccount.getAccount_id(),
                firstAccount.getAccount_id()
            ),
            orderRepository.findAccountIds(owner.getId())
        );

        assertEquals(
            1L,
            orderRepository.findVersion(owner.getId())
                .orElseThrow().longValue()
        );

        var repeatedResult = service.saveOrder(
            commandId, request, owner
        );

        assertEquals(firstResult, repeatedResult);

        assertEquals(
            1L,
            orderRepository.findVersion(owner.getId())
                .orElseThrow().longValue()
        );

        assertEquals(
            firstResult,
            commandRepository.findCompleted(
                owner.getId(), commandId
            ).orElseThrow()
        );
    }

    @Test
    void outdatedVersionDoesNotChangeSavedOrder() {
        service.saveOrder(
            UUID.randomUUID(),
            new UpdateAccountOrderRequest(
                List.of(firstAccount.getSyncId(), secondAccount.getSyncId()),
                0L
            ),
            owner
        );

        UUID conflictingCommandId = UUID.randomUUID();

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.saveOrder(
                conflictingCommandId,
                new UpdateAccountOrderRequest(
                    List.of(
                        secondAccount.getSyncId(),
                        firstAccount.getSyncId()
                    ),
                    0L
                ),
                owner
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());

        assertEquals(
            List.of(
                firstAccount.getAccount_id(),
                secondAccount.getAccount_id()
            ),
            orderRepository.findAccountIds(owner.getId())
        );

        assertEquals(
            1L,
            orderRepository.findVersion(owner.getId())
                .orElseThrow().longValue()
        );

        assertTrue(
            commandRepository.findCompleted(
                owner.getId(), conflictingCommandId
            ).isEmpty()
        );
    }

    @Test
    void rollsBackPositionsVersionAndCommandTogether() {
        service.saveOrder(
            UUID.randomUUID(),
            new UpdateAccountOrderRequest(
                List.of(firstAccount.getSyncId(), secondAccount.getSyncId()),
                0L
            ),
            owner
        );

        UUID commandId = UUID.randomUUID();

        var request = new UpdateAccountOrderRequest(
            List.of(secondAccount.getSyncId(), firstAccount.getSyncId()),
            1L
        );

        TransactionTemplate transaction =
            new TransactionTemplate(transactionManager);

        assertThrows(
            IllegalStateException.class,
            () -> transaction.executeWithoutResult(status -> {
                service.saveOrder(commandId, request, owner);

                throw new IllegalStateException(
                    "Simulated transaction failure"
                );
            })
        );

        assertEquals(
            List.of(
                firstAccount.getAccount_id(),
                secondAccount.getAccount_id()
            ),
            orderRepository.findAccountIds(owner.getId())
        );

        assertEquals(
            1L,
            orderRepository.findVersion(owner.getId())
                .orElseThrow().longValue()
        );

        assertTrue(
            commandRepository.findCompleted(
                owner.getId(), commandId
            ).isEmpty()
        );

        // The rolled-back command can be retried.
        service.saveOrder(commandId, request, owner);

        assertEquals(
            List.of(
                secondAccount.getAccount_id(),
                firstAccount.getAccount_id()
            ),
            orderRepository.findAccountIds(owner.getId())
        );

        assertEquals(
            2L,
            orderRepository.findVersion(owner.getId())
                .orElseThrow().longValue()
        );
    }

    @Test
    void concurrentChangesAllowOnlyOneOrderToBeSaved() throws Exception {
        var firstRequest = new UpdateAccountOrderRequest(
            List.of(firstAccount.getSyncId(), secondAccount.getSyncId()),
            0L
        );

        var secondRequest = new UpdateAccountOrderRequest(
            List.of(secondAccount.getSyncId(), firstAccount.getSyncId()),
            0L
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Integer> first = executor.submit(() ->
                submitConcurrentOrder(firstRequest, ready, start)
            );

            Future<Integer> second = executor.submit(() ->
                submitConcurrentOrder(secondRequest, ready, start)
            );

            assertTrue(
                ready.await(10, TimeUnit.SECONDS),
                "Both requests must be ready"
            );

            start.countDown();

            List<Integer> statuses = List.of(
                first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS)
            ).stream().sorted().toList();

            assertEquals(List.of(200, 409), statuses);

            assertEquals(
                1L,
                orderRepository.findVersion(owner.getId())
                    .orElseThrow().longValue()
            );

            List<Long> savedOrder = orderRepository.findAccountIds(
                owner.getId()
            );

            assertTrue(
                savedOrder.equals(List.of(
                    firstAccount.getAccount_id(),
                    secondAccount.getAccount_id()
                ))
                || savedOrder.equals(List.of(
                    secondAccount.getAccount_id(),
                    firstAccount.getAccount_id()
                )),
                "The saved order must match one complete request"
            );

            assertEquals(
                Long.valueOf(1L),
                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM sync_command
                    WHERE user_id = ?
                    AND operation = 'UPDATE_ACCOUNT_ORDER'
                    """,
                    Long.class,
                    owner.getId()
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

    @Test
    void deletingAccountRemovesItsPositionAndIncrementsOrderVersion() {
        service.saveOrder(
            UUID.randomUUID(),
            new UpdateAccountOrderRequest(
                List.of(
                    firstAccount.getSyncId(),
                    secondAccount.getSyncId()
                ),
                0L
            ),
            owner
        );

        boolean deleted = accountService.deleteAccount(
            firstAccount.getAccount_id(),
            owner
        );

        assertTrue(deleted);

        assertEquals(
            List.of(secondAccount.getAccount_id()),
            orderRepository.findAccountIds(owner.getId())
        );

        assertEquals(
            2L,
            orderRepository.findVersion(owner.getId())
                .orElseThrow().longValue()
        );

        var response = service.getOrder(owner);

        assertEquals(
            List.of(secondAccount.getSyncId()),
            response.accountSyncIds()
        );
        assertEquals(2L, response.version());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.saveOrder(
                UUID.randomUUID(),
                new UpdateAccountOrderRequest(
                    List.of(secondAccount.getSyncId()),
                    1L
                ),
                owner
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }
}