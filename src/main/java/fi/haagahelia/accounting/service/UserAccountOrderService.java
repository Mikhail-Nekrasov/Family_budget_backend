package fi.haagahelia.accounting.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.AccountOrderResponse;
import fi.haagahelia.accounting.api.dto.UpdateAccountOrderRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserAccountOrderRepository;
import fi.haagahelia.accounting.repository.UserAccountOrderRepository.OrderSnapshot;

@Service
public class UserAccountOrderService {

    private final UserAccountOrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final AccountAccessService accountAccessService;
    private final SyncCommandService syncCommandService;

    public UserAccountOrderService(
            UserAccountOrderRepository orderRepository,
            AccountRepository accountRepository,
            AccountAccessService accountAccessService,
            SyncCommandService syncCommandService) {

        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.accountAccessService = accountAccessService;
        this.syncCommandService = syncCommandService;
    }

    @Transactional
    public CompletedCommand saveOrder(
            UUID commandId,
            UpdateAccountOrderRequest request,
            User currentUser) {

        if (commandId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Command ID is required"
            );
        }

        Long expectedVersion = request.expectedVersion();

        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A non-negative expected version is required"
            );
        }

        List<UUID> requestedIds = request.accountSyncIds();

        if (requestedIds == null
            || requestedIds.stream().anyMatch(id -> id == null)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Account sync IDs are required and must not contain null"
                );
        }

        if (new HashSet<>(requestedIds).size() != requestedIds.size()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account sync IDs must not contain duplicates"
            );
        }

        List<UUID> syncIds = List.copyOf(requestedIds);

        UpdateAccountOrderRequest normalized =
            new UpdateAccountOrderRequest(syncIds, expectedVersion);

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "UPDATE_ACCOUNT_ORDER",
            normalized,
            HttpStatus.OK.value(),
            () -> {
                List<Long> accountIds = new ArrayList<>();

                for (UUID syncId : syncIds) {
                    Account account = accountRepository.findBySyncId(syncId)
                        .orElseThrow(() -> unavailableAccount());

                    if (!accountAccessService.canRead(account, currentUser)) {
                        throw unavailableAccount();
                    }

                    accountIds.add(account.getAccount_id());
                }

                orderRepository.createIfMissing(currentUser.getId());

                boolean saved = orderRepository.replaceOrder(
                    currentUser.getId(),
                    expectedVersion,
                    accountIds
                );

                if (!saved) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Account order version conflict. Synchronize before retrying."
                    );
                }

                return new AccountOrderResponse(
                    syncIds,
                    expectedVersion + 1
                );
            }
        );
    }

    private ResponseStatusException unavailableAccount() {
        return new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Account is unavailable"
        );
    }

    @Transactional(readOnly = true)
    public AccountOrderResponse getOrder(User currentUser) {
        OrderSnapshot snapshot = orderRepository
            .findSnapshot(currentUser.getId())
            .orElseGet(() -> new OrderSnapshot(List.of(), 0L));

        var accountsById = new HashMap<Long, Account>();

        for (Account account : accountRepository.findAllById(
                snapshot.accountIds())) {
            accountsById.put(account.getAccount_id(), account);
        }

        List<UUID> syncIds = new ArrayList<>();

        for (Long accountId : snapshot.accountIds()) {
            Account account = accountsById.get(accountId);

            if (account != null
                    && accountAccessService.canRead(account, currentUser)) {
                syncIds.add(account.getSyncId());
            }
        }

        return new AccountOrderResponse(
            List.copyOf(syncIds),
            snapshot.version()
        );
    }
}