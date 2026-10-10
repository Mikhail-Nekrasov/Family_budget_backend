package fi.haagahelia.accounting.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}