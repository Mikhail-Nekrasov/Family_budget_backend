package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.AccountOrderResponse;
import fi.haagahelia.accounting.api.dto.UpdateAccountOrderRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserAccountOrderRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class UserAccountOrderServiceTest {

    @Mock
    private UserAccountOrderRepository orderRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountAccessService accountAccessService;

    @Mock
    private SyncCommandRepository commandRepository;

    private UserAccountOrderService service;
    private JsonMapper jsonMapper;
    private User user;
    private Account account;
    private UUID commandId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setUsername("testuser");

        account = new Account(
            "Card",
            BigDecimal.ZERO,
            null,
            user
        );

        ReflectionTestUtils.setField(account, "account_id", 10L);

        commandId = UUID.randomUUID();
        jsonMapper = JsonMapper.builder().build();

        SyncCommandService commandService = new SyncCommandService(
            commandRepository,
            jsonMapper
        );

        service = new UserAccountOrderService(
            orderRepository,
            accountRepository,
            accountAccessService,
            commandService
        );
    }

    private void allowAccount() {
        when(accountRepository.findBySyncId(account.getSyncId()))
            .thenReturn(Optional.of(account));

        when(accountAccessService.canRead(account, user))
            .thenReturn(true);
    }

    @Test
    void savesRequestedOrderAndReturnsNewVersion() {
        Account secondAccount = new Account(
            "Cash",
            BigDecimal.ZERO,
            null,
            user
        );

        ReflectionTestUtils.setField(
            secondAccount, "account_id", 20L
        );

        allowAccount();

        when(accountRepository.findBySyncId(secondAccount.getSyncId()))
            .thenReturn(Optional.of(secondAccount));

        when(accountAccessService.canRead(secondAccount, user))
            .thenReturn(true);

        when(orderRepository.replaceOrder(
                1L, 3L, List.of(20L, 10L)))
            .thenReturn(true);

        var request = new UpdateAccountOrderRequest(
            List.of(secondAccount.getSyncId(), account.getSyncId()),
            3L
        );

        CompletedCommand result = service.saveOrder(
            commandId, request, user
        );

        AccountOrderResponse response = jsonMapper.readValue(
            result.responseBody(),
            AccountOrderResponse.class
        );

        assertEquals(200, result.responseStatus());
        assertEquals(request.accountSyncIds(), response.accountSyncIds());
        assertEquals(4L, response.version());

        verify(commandRepository).lockUser(1L);
        verify(orderRepository).createIfMissing(1L);
        verify(orderRepository).replaceOrder(
            1L, 3L, List.of(20L, 10L)
        );
        verify(commandRepository).saveCompleted(
            eq(1L), eq(commandId), eq(result)
        );
    }

    @Test
    void repeatedCommandReturnsPreviousResponseWithoutWritingAgain() {
        allowAccount();

        when(orderRepository.replaceOrder(1L, 0L, List.of(10L)))
            .thenReturn(true);

        var request = new UpdateAccountOrderRequest(
            List.of(account.getSyncId()),
            0L
        );

        CompletedCommand first = service.saveOrder(
            commandId, request, user
        );

        when(commandRepository.findCompleted(1L, commandId))
            .thenReturn(Optional.of(first));

        CompletedCommand repeated = service.saveOrder(
            commandId, request, user
        );

        assertEquals(first, repeated);

        verify(orderRepository, times(1)).replaceOrder(
            1L, 0L, List.of(10L)
        );
        verify(commandRepository, times(1)).saveCompleted(
            eq(1L), eq(commandId), any(CompletedCommand.class)
        );
    }

    @Test
    void rejectsInvalidRequestsBeforeAccessingRepositories() {
        UUID syncId = account.getSyncId();

        List<UpdateAccountOrderRequest> invalidRequests = List.of(
            new UpdateAccountOrderRequest(null, 0L),
            new UpdateAccountOrderRequest(
                Arrays.asList(syncId, null), 0L
            ),
            new UpdateAccountOrderRequest(
                List.of(syncId, syncId), 0L
            ),
            new UpdateAccountOrderRequest(List.of(syncId), null),
            new UpdateAccountOrderRequest(List.of(syncId), -1L)
        );

        for (var request : invalidRequests) {
            ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.saveOrder(commandId, request, user)
            );

            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }

        verifyNoInteractions(
            orderRepository,
            accountRepository,
            accountAccessService,
            commandRepository
        );
    }

    @Test
    void rejectsUnknownAccountWithoutChangingOrder() {
        UUID unknownId = UUID.randomUUID();

        when(accountRepository.findBySyncId(unknownId))
            .thenReturn(Optional.empty());

        var request = new UpdateAccountOrderRequest(
            List.of(unknownId), 0L
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.saveOrder(commandId, request, user)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(orderRepository);
        verify(commandRepository, never()).saveCompleted(
            anyLong(), any(UUID.class), any(CompletedCommand.class)
        );
    }

    @Test
    void rejectsAccountWithoutReadAccess() {
        when(accountRepository.findBySyncId(account.getSyncId()))
            .thenReturn(Optional.of(account));

        when(accountAccessService.canRead(account, user))
            .thenReturn(false);

        var request = new UpdateAccountOrderRequest(
            List.of(account.getSyncId()), 0L
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.saveOrder(commandId, request, user)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(orderRepository);
        verify(commandRepository, never()).saveCompleted(
            anyLong(), any(UUID.class), any(CompletedCommand.class)
        );
    }

    @Test
    void rejectsOutdatedVersion() {
        allowAccount();

        when(orderRepository.replaceOrder(1L, 2L, List.of(10L)))
            .thenReturn(false);

        var request = new UpdateAccountOrderRequest(
            List.of(account.getSyncId()), 2L
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.saveOrder(commandId, request, user)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(commandRepository, never()).saveCompleted(
            anyLong(), any(UUID.class), any(CompletedCommand.class)
        );
    }

    @Test
    void allowsEmptyOrderWithoutDeletingAccounts() {
        when(orderRepository.replaceOrder(1L, 0L, List.of()))
            .thenReturn(true);

        CompletedCommand result = service.saveOrder(
            commandId,
            new UpdateAccountOrderRequest(List.of(), 0L),
            user
        );

        AccountOrderResponse response = jsonMapper.readValue(
            result.responseBody(),
            AccountOrderResponse.class
        );

        assertTrue(response.accountSyncIds().isEmpty());
        assertEquals(1L, response.version());

        verifyNoInteractions(accountRepository, accountAccessService);
        verify(orderRepository).replaceOrder(1L, 0L, List.of());
    }
}