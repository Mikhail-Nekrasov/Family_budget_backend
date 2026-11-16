package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.SyncCreateTransferRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteResponse;
import fi.haagahelia.accounting.api.dto.SyncDeleteTransferRequest;
import fi.haagahelia.accounting.api.dto.SyncUpdateTransferRequest;
import fi.haagahelia.accounting.api.dto.TransferResponse;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.SyncTombstoneRepository;
import fi.haagahelia.accounting.service.TransferService.TransferResult;

@Service
public class TransferSyncService {

    private final PersonalSyncPolicy personalSyncPolicy;

    private final TransferService transferService;
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final SyncCommandService syncCommandService;
    private final SyncTombstoneRepository tombstones;

    public TransferSyncService(
            TransferService transferService,
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            SyncCommandService syncCommandService,
            PersonalSyncPolicy personalSyncPolicy,
            SyncTombstoneRepository tombstones) {

        this.personalSyncPolicy = personalSyncPolicy;

        this.transferService = transferService;
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.syncCommandService = syncCommandService;
        this.tombstones = tombstones;
    }

    @Transactional
    public CompletedCommand createTransfer(
            UUID commandId,
            SyncCreateTransferRequest request,
            User currentUser) {

        UUID syncId = request.syncId();
        UUID fromSyncId = request.fromAccountSyncId();
        UUID toSyncId = request.toAccountSyncId();

        if (syncId == null || fromSyncId == null || toSyncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Transfer and account sync IDs are required"
            );
        }

        if (request.dateTime() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Date and time are required"
            );
        }

        var normalized = new SyncCreateTransferRequest(
            syncId, fromSyncId, toSyncId, normalizeAmount(request.amount()),
            request.dateTime(), request.description(), normalizeAmount(request.targetAmount())
        );

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "CREATE_TRANSFER",
            normalized,
            HttpStatus.CREATED.value(),
            () -> {
                if (tombstones.transferWasDeleted(currentUser.getId(), syncId)) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Transfer was deleted and cannot be recreated with the same sync ID"
                    );
                }

                Account from = requireAccount(fromSyncId, currentUser, "Source");
                Account to = requireAccount(toSyncId, currentUser, "Destination");

                var accounts = new AccountVersionChanges(from, to);
                TransferResult result =
                    transferService.executeTransferWithAmounts(
                        from.getAccount_id(),
                        to.getAccount_id(),
                        normalized.amount(),
                        normalized.targetAmount(),
                        normalized.description(),
                        currentUser,
                        syncId, normalized.dateTime()
                    );

                requireSuccess(result);

                var saved = transferRepository.findBySyncId(syncId)
                    .orElseThrow(() -> new IllegalStateException(
                        "Created transfer was not found"
                    ));

                transferRepository.flush();
                return syncCommandService.withAccountVersionChanges(
                    TransferResponse.from(saved), accounts
                );
            }
        );
    }

    @Transactional
    public CompletedCommand updateTransfer(
            UUID commandId, SyncUpdateTransferRequest request, User currentUser) {
        requireVersionedTarget(request.syncId(), request.expectedVersion());
        if (request.fromAccountSyncId() == null || request.toAccountSyncId() == null
                || request.dateTime() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Account sync IDs and date and time are required"
            );
        }

        var normalized = new SyncUpdateTransferRequest(
            request.syncId(), request.expectedVersion(), request.fromAccountSyncId(),
            request.toAccountSyncId(), normalizeAmount(request.amount()), request.dateTime(),
            request.description(), normalizeAmount(request.targetAmount())
        );
        return syncCommandService.execute(
            currentUser.getId(), commandId, "UPDATE_TRANSFER", normalized, HttpStatus.OK.value(),
            () -> {
                Transfer transfer = requireOwnedTransfer(
                    normalized.syncId(), normalized.expectedVersion(), currentUser
                );
                Account from = requireAccount(normalized.fromAccountSyncId(), currentUser, "Source");
                Account to = requireAccount(normalized.toAccountSyncId(), currentUser, "Destination");
                if ((from.getArchivedAt() != null && !from.getAccount_id().equals(transfer.getFromAccount().getAccount_id()))
                        || (to.getArchivedAt() != null && !to.getAccount_id().equals(transfer.getToAccount().getAccount_id()))) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "New transfer accounts must be active"
                    );
                }

                var accounts = new AccountVersionChanges(
                    transfer.getFromAccount(), transfer.getToAccount(), from, to
                );
                requireSuccess(transferService.updateTransfer(
                    transfer, from, to, normalized.amount(), normalized.targetAmount(),
                    normalized.dateTime(), normalized.description()
                ));
                transferRepository.flush();
                return syncCommandService.withAccountVersionChanges(
                    TransferResponse.from(transfer), accounts
                );
            }
        );
    }

    @Transactional
    public CompletedCommand deleteTransfer(
            UUID commandId, SyncDeleteTransferRequest request, User currentUser) {
        requireVersionedTarget(request.syncId(), request.expectedVersion());
        return syncCommandService.execute(
            currentUser.getId(), commandId, "DELETE_TRANSFER", request, HttpStatus.OK.value(),
            () -> {
                Transfer transfer = requireOwnedTransfer(
                    request.syncId(), request.expectedVersion(), currentUser
                );
                var accounts = new AccountVersionChanges(transfer.getFromAccount(), transfer.getToAccount());
                transferService.deleteTransfer(transfer);
                transferRepository.flush();
                tombstones.recordTransferDeletion(currentUser.getId(), request.syncId());
                return syncCommandService.withAccountVersionChanges(
                    new SyncDeleteResponse(request.syncId(), true), accounts
                );
            }
        );
    }

    private Transfer requireOwnedTransfer(UUID syncId, Long expectedVersion, User currentUser) {
        Transfer transfer = transferRepository.findOwnedBySyncId(syncId, currentUser)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Transfer is unavailable for synchronization"
            ));
        personalSyncPolicy.requirePrivateAccount(transfer.getFromAccount());
        personalSyncPolicy.requirePrivateAccount(transfer.getToAccount());
        if (!expectedVersion.equals(transfer.getVersion())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Transfer version conflict. Synchronize before retrying."
            );
        }
        return transfer;
    }

    private Account requireAccount(UUID syncId, User currentUser, String side) {
        Account account = accountRepository.findOwnedBySyncId(syncId, currentUser)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, side + " account is unavailable for synchronization"
            ));
        personalSyncPolicy.requirePrivateAccount(account);
        return account;
    }

    private void requireVersionedTarget(UUID syncId, Long expectedVersion) {
        if (syncId == null || expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Transfer sync ID and a non-negative expected version are required"
            );
        }
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros();
    }

    private void requireSuccess(TransferResult result) {
        switch (result) {
            case SUCCESS -> {
                return;
            }

            case INVALID_ACCOUNTS -> throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Accounts are unavailable or access is denied"
            );

            case SAME_ACCOUNT -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Source and destination accounts must be different"
            );

            case DIFFERENT_CURRENCIES -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Destination amount is required for transfers between different currencies"
            );

            case INVALID_AMOUNT -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Amount must be greater than zero "
                    + "and use the source currency precision"
            );

            case INVALID_TARGET_AMOUNT -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Destination amount must be positive, use the destination currency precision "
                    + "and equal the source amount for same-currency transfers"
            );
        }
    }
}