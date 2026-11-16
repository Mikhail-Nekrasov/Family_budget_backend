package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        INVALID_TARGET_AMOUNT
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
            .map(TransferResponse::from);
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

        return executeTransferWithAmounts(
            fromId, toId, amount, targetAmount, description, currentUser, syncId, null
        );
    }

    TransferResult executeTransferWithAmounts(
            Long fromId, Long toId, BigDecimal amount,
            @Nullable BigDecimal targetAmount, String description, User currentUser,
            @Nullable UUID syncId, @Nullable LocalDateTime dateTime) {

        Account from = accountRepository.findById(fromId).orElse(null);
        Account to = accountRepository.findById(toId).orElse(null);

        if (from == null || to == null
                || !accountAccessService.canEdit(from, currentUser)
                || !accountAccessService.canRead(to, currentUser)) {
            return TransferResult.INVALID_ACCOUNTS;
        }

        TransferResult validation = validateAmounts(from, to, amount, targetAmount);
        if (validation != TransferResult.SUCCESS) {
            return validation;
        }

        Transfer transfer = new Transfer(from, to, amount, description);
        transfer.setTargetAmount(targetAmount != null ? targetAmount : amount);
        if (dateTime != null) {
            transfer.setDateTime(dateTime);
        }

        if (syncId != null) {
            transfer.assignSyncId(syncId);
        }

        if (transferRepository.existsBySyncId(transfer.getSyncId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transfer sync ID is already in use"
            );
        }

        applyBalances(from, to, transfer.getAmount(), transfer.getTargetAmount());

        accountRepository.save(from);
        accountRepository.save(to);
        transferRepository.save(transfer);

        return TransferResult.SUCCESS;
    }

    TransferResult updateTransfer(
            Transfer transfer, Account from, Account to, BigDecimal amount,
            @Nullable BigDecimal targetAmount, LocalDateTime dateTime, String description) {
        TransferResult validation = validateAmounts(from, to, amount, targetAmount);
        if (validation != TransferResult.SUCCESS) {
            return validation;
        }

        reverseBalances(transfer);
        BigDecimal credit = targetAmount != null ? targetAmount : amount;
        applyBalances(from, to, amount, credit);
        transfer.setFromAccount(from);
        transfer.setToAccount(to);
        transfer.setAmount(amount);
        transfer.setTargetAmount(credit);
        transfer.setDateTime(dateTime);
        transfer.setDescription(description);
        return TransferResult.SUCCESS;
    }

    void deleteTransfer(Transfer transfer) {
        reverseBalances(transfer);
        transferRepository.delete(transfer);
    }

    private void reverseBalances(Transfer transfer) {
        applyBalances(transfer.getFromAccount(), transfer.getToAccount(),
            transfer.getAmount().negate(), transfer.getTargetAmount().negate());
    }

    private void applyBalances(Account from, Account to, BigDecimal debit, BigDecimal credit) {
        from.setAmount(from.getAmount().subtract(debit));
        to.setAmount(to.getAmount().add(credit));
    }

    private TransferResult validateAmounts(
            Account from, Account to, BigDecimal amount, @Nullable BigDecimal targetAmount) {
        if (from.getAccount_id().equals(to.getAccount_id())) {
            return TransferResult.SAME_ACCOUNT;
        }

        if (amount == null
                || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.stripTrailingZeros().scale() > AmountPrecision.fractionDigits(from)) {
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
                || creditedAmount.stripTrailingZeros().scale() > AmountPrecision.fractionDigits(to)) {
            return TransferResult.INVALID_TARGET_AMOUNT;
        }

        if (sameCurrency && creditedAmount.compareTo(amount) != 0) {
            return TransferResult.INVALID_TARGET_AMOUNT;
        }

        return TransferResult.SUCCESS;
    }
}
