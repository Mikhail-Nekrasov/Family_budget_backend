package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.AccountResponse;
import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteAccountRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteResponse;
import fi.haagahelia.accounting.api.dto.SyncUpdateAccountRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;

@Service
public class AccountSyncService {

    private final AccountService accountService;
    private final SyncCommandService syncCommandService;
    private final AccountRepository accountRepository;

    public AccountSyncService(
            AccountService accountService,
            SyncCommandService syncCommandService,
            AccountRepository accountRepository) {

        this.accountService = accountService;
        this.syncCommandService = syncCommandService;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public CompletedCommand createAccount(
            UUID commandId,
            CreateAccountRequest request,
            User currentUser) {

        UUID syncId = request.syncId();

        if (syncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Sync ID is required for synchronization"
            );
        }

        BigDecimal amount = request.amount();

        amount = amount == null
            ? BigDecimal.ZERO
            : amount.stripTrailingZeros();

        CreateAccountRequest normalized = new CreateAccountRequest(
            request.name(),
            amount,
            request.description(),
            syncId
        );

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "CREATE_ACCOUNT",
            normalized,
            HttpStatus.CREATED.value(),
            () -> {
                Account account = new Account(
                    normalized.name(),
                    normalized.amount(),
                    normalized.description(),
                    currentUser
                );

                account.assignSyncId(syncId);

                Account saved = accountService.createAccount(
                    account, currentUser
                );

                return new AccountResponse(
                    saved.getAccount_id(),
                    saved.getName(),
                    saved.getAmount(),
                    saved.getDescription(),
                    saved.getSyncId(),
                    saved.getVersion()
                );
            }
        );
    }

    @Transactional
    public CompletedCommand updateAccount(
            UUID commandId,
            SyncUpdateAccountRequest request,
            User currentUser) {

        UUID syncId = request.syncId();
        Long expectedVersion = request.expectedVersion();

        if (syncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account sync ID is required"
            );
        }

        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A non-negative expected version is required"
            );
        }

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "UPDATE_ACCOUNT",
            request,
            HttpStatus.OK.value(),
            () -> {
                var account = accountRepository.findOwnedBySyncId(
                    syncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Account is unavailable for synchronization"
                ));

                if (!expectedVersion.equals(account.getVersion())) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Account version conflict. Synchronize before retrying."
                    );
                }

                if (request.name() == null || request.name().isBlank()) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Account name is required"
                    );
                }

                account.setName(request.name());
                account.setDescription(request.description());

                accountRepository.flush();

                return new AccountResponse(
                    account.getAccount_id(),
                    account.getName(),
                    account.getAmount(),
                    account.getDescription(),
                    account.getSyncId(),
                    account.getVersion()
                );
            }
        );
    }

    @Transactional
    public CompletedCommand deleteAccount(
            UUID commandId,
            SyncDeleteAccountRequest request,
            User currentUser) {

        UUID syncId = request.syncId();
        Long expectedVersion = request.expectedVersion();

        if (syncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account sync ID is required"
            );
        }

        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A non-negative expected version is required"
            );
        }

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "DELETE_ACCOUNT",
            request,
            HttpStatus.OK.value(),
            () -> {
                Account account = accountRepository.findOwnedBySyncId(
                    syncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Account is unavailable for synchronization"
                ));

                if (!expectedVersion.equals(account.getVersion())) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Account version conflict. Synchronize before retrying."
                    );
                }

                boolean deleted = accountService.deleteAccount(
                    account.getAccount_id(), currentUser
                );

                if (!deleted) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Account with entries or transfers cannot be deleted"
                    );
                }

                accountRepository.flush();

                return new SyncDeleteResponse(syncId, true);
            }
        );
    }
}