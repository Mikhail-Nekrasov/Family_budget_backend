package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.SyncCreateTransferRequest;
import fi.haagahelia.accounting.api.dto.TransferResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.service.TransferService.TransferResult;

@Service
public class TransferSyncService {

    private final PersonalSyncPolicy personalSyncPolicy;

    private final TransferService transferService;
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final SyncCommandService syncCommandService;

    public TransferSyncService(
            TransferService transferService,
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            SyncCommandService syncCommandService,
            PersonalSyncPolicy personalSyncPolicy) {

        this.personalSyncPolicy = personalSyncPolicy;

        this.transferService = transferService;
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.syncCommandService = syncCommandService;
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

        BigDecimal amount = request.amount();
        BigDecimal targetAmount = request.targetAmount();

        SyncCreateTransferRequest normalized =
            new SyncCreateTransferRequest(
                syncId,
                fromSyncId,
                toSyncId,
                amount == null ? null : amount.stripTrailingZeros(),
                request.dateTime(),
                request.description(),
                targetAmount == null
                    ? null
                    : targetAmount.stripTrailingZeros()
            );

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "CREATE_TRANSFER",
            normalized,
            HttpStatus.CREATED.value(),
            () -> {
                var from = accountRepository.findOwnedBySyncId(
                    fromSyncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source account is unavailable for synchronization"
                ));

                var to = accountRepository.findOwnedBySyncId(
                    toSyncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Destination account is unavailable for synchronization"
                ));

                personalSyncPolicy.requirePrivateAccount(from);
                personalSyncPolicy.requirePrivateAccount(to);

                TransferResult result =
                    transferService.executeTransferWithAmounts(
                        from.getAccount_id(),
                        to.getAccount_id(),
                        normalized.amount(),
                        normalized.targetAmount(),
                        normalized.description(),
                        currentUser,
                        syncId
                    );

                requireSuccess(result);

                var saved = transferRepository.findBySyncId(syncId)
                    .orElseThrow(() -> new IllegalStateException(
                        "Created transfer was not found"
                    ));

                saved.setDateTime(normalized.dateTime());

                return new TransferResponse(
                    saved.getId(),
                    saved.getFromAccount().getAccount_id(),
                    saved.getFromAccount().getName(),
                    saved.getToAccount().getAccount_id(),
                    saved.getToAccount().getName(),
                    saved.getAmount(),
                    saved.getDateTime(),
                    saved.getDescription(),
                    saved.getSyncId(),
                    saved.getTargetAmount(),
                    saved.getSourceCurrencyCode(),
                    saved.getTargetCurrencyCode()
                );
            }
        );
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
                    + "and have at most two decimal places"
            );

            case INVALID_TARGET_AMOUNT -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Destination amount must be positive, have at most two decimal places "
                    + "and equal the source amount for same-currency transfers"
            );

            case INSUFFICIENT_FUNDS -> throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Insufficient funds"
            );
        }
    }
}