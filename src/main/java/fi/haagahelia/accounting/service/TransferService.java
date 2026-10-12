package fi.haagahelia.accounting.service;

import java.math.BigDecimal;

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
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS
    }

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;

    public TransferService(AccountRepository accountRepository,
                           TransferRepository transferRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public TransferResult executeTransfer(Long fromId,
                                          Long toId,
                                          BigDecimal amount,
                                          String description,
                                          User currentUser) {

        Account from = accountRepository.findById(fromId).orElse(null);
        Account to = accountRepository.findById(toId).orElse(null);

        if (from == null || to == null
                || from.getUser() == null || to.getUser() == null
                || !currentUser.getId().equals(from.getUser().getId())
                || !currentUser.getId().equals(to.getUser().getId())) {
            return TransferResult.INVALID_ACCOUNTS;
        }

        if (fromId.equals(toId)) {
            return TransferResult.SAME_ACCOUNT;
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferResult.INVALID_AMOUNT;
        }

        if (from.getAmount().compareTo(amount) < 0) {
            return TransferResult.INSUFFICIENT_FUNDS;
        }

        from.setAmount(from.getAmount().subtract(amount));
        to.setAmount(to.getAmount().add(amount));

        accountRepository.save(from);
        accountRepository.save(to);

        Transfer transfer = new Transfer(from, to, amount, description);
        transferRepository.save(transfer);

        return TransferResult.SUCCESS;
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
                transfer.getDescription()
            ));
    }
}