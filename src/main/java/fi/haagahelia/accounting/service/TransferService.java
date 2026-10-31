package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.TransferResponse;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;

@Service
public class TransferService {

    public enum TransferResult {
        SUCCESS,
        INVALID_ACCOUNTS,
        SAME_ACCOUNT,
        DIFFERENT_CURRENCIES,
        INVALID_AMOUNT,
        INVALID_TARGET_AMOUNT,
        INSUFFICIENT_FUNDS
    }

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final AccountAccessService accountAccessService;

    public TransferService(AccountRepository accountRepository,
                        TransferRepository transferRepository,
                        AccountAccessService accountAccessService) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.accountAccessService = accountAccessService;
    }

    @Transactional
    public TransferResult executeTransfer(
            Long fromId,
            Long toId,
            BigDecimal amount,
            String description,
            User currentUser) {

        return executeTransferWithSyncId(
            fromId, toId, amount, description, currentUser, null
        );
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> getTransfers(
            User currentUser, int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be non-negative and size must be between 1 and 100"
            );
        }

        return transferRepository.findVisiblePageByUser(
                currentUser, PageRequest.of(page, size)
            )
            .map(transfer -> new TransferResponse(
                transfer.getId(),
                transfer.getFromAccount().getAccount_id(),
                transfer.getFromAccount().getName(),
                transfer.getToAccount().getAccount_id(),
                transfer.getToAccount().getName(),
                transfer.getAmount(),
                transfer.getDateTime(),
                transfer.getDescription(),
                transfer.getSyncId(),
                transfer.getTargetAmount(),
                transfer.getSourceCurrencyCode(),
                transfer.getTargetCurrencyCode()
            ));
    }

    @Transactional
    public TransferResult executeTransferWithSyncId(
            Long fromId,
            Long toId,
            BigDecimal amount,
            String description,
            User currentUser,
            @Nullable UUID syncId) {

        return executeTransferWithAmounts(
            fromId,
            toId,
            amount,
            null,
            description,
            currentUser,
            syncId
        );
    }

    @Transactional
    public TransferResult executeTransferWithAmounts(
            Long fromId,
            Long toId,
            BigDecimal amount,
            @Nullable BigDecimal targetAmount,
            String description,
            User currentUser,
            @Nullable UUID syncId) {

        Account from = accountRepository.findById(fromId).orElse(null);
        Account to = accountRepository.findById(toId).orElse(null);

        if (from == null || to == null
                || !accountAccessService.canEdit(from, currentUser)
                || !accountAccessService.canRead(to, currentUser)) {
            return TransferResult.INVALID_ACCOUNTS;
        }

        if (fromId.equals(toId)) {
            return TransferResult.SAME_ACCOUNT;
        }

        if (amount == null
                || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.stripTrailingZeros().scale() > 2) {
            return TransferResult.INVALID_AMOUNT;
        }

        boolean sameCurrency = from.getCurrencyCode()
            .equals(to.getCurrencyCode());

        BigDecimal creditedAmount = targetAmount;

        if (creditedAmount == null) {
            if (!sameCurrency) {
                return TransferResult.DIFFERENT_CURRENCIES;
            }

            creditedAmount = amount;
        }

        if (creditedAmount.compareTo(BigDecimal.ZERO) <= 0
                || creditedAmount.stripTrailingZeros().scale() > 2) {
            return TransferResult.INVALID_TARGET_AMOUNT;
        }

        if (sameCurrency && creditedAmount.compareTo(amount) != 0) {
            return TransferResult.INVALID_TARGET_AMOUNT;
        }

        Transfer transfer = new Transfer(
            from, to, amount, description
        );

        transfer.setTargetAmount(creditedAmount);

        if (syncId != null) {
            transfer.assignSyncId(syncId);
        }

        if (transferRepository.existsBySyncId(transfer.getSyncId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transfer sync ID is already in use"
            );
        }

        if (from.getAmount().compareTo(amount) < 0) {
            return TransferResult.INSUFFICIENT_FUNDS;
        }

        from.setAmount(from.getAmount().subtract(amount));
        to.setAmount(to.getAmount().add(creditedAmount));

        accountRepository.save(from);
        accountRepository.save(to);
        transferRepository.save(transfer);

        return TransferResult.SUCCESS;
    }
}