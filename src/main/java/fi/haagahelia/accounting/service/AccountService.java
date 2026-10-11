package fi.haagahelia.accounting.service;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public void createAccount(Account account, User currentUser) {
        if (account.getName() == null || account.getName().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account name is required"
            );
        }

        account.setUser(currentUser);

        if (account.getAmount() == null) {
            account.setAmount(BigDecimal.ZERO);
        }

        accountRepository.save(account);
    }

    @Transactional
    public void deleteAccount(Long id, User currentUser) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Account not found"
            ));

        if (account.getUser() == null
                || !currentUser.getId().equals(account.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "You cannot delete another user's account"
            );
        }

        boolean hasEntries = account.getEntries() != null
            && !account.getEntries().isEmpty();

        boolean hasTransfers =
            (account.getFromTransfers() != null
                && !account.getFromTransfers().isEmpty())
            || (account.getToTransfers() != null
                && !account.getToTransfers().isEmpty());

        if (!hasEntries && !hasTransfers) {
            accountRepository.deleteById(id);
        }
    }
}