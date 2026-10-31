package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.AccountResponse;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.SyncTombstoneRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;
    private final SyncTombstoneRepository syncTombstoneRepository;

    public AccountService(AccountRepository accountRepository,
                        EntryRepository entryRepository,
                        SyncTombstoneRepository syncTombstoneRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.syncTombstoneRepository = syncTombstoneRepository;
    }

    @Transactional
    public Account createAccount(Account account, User currentUser) {
        if (account.getName() == null || account.getName().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account name is required"
            );
        }

        String currencyCode = account.getCurrencyCode();

        if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A three-letter currency code is required"
            );
        }

        Currency currency;

        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unknown currency code"
            );
        }

        if (currency.getDefaultFractionDigits() != 2) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only currencies with two decimal places are currently supported"
            );
        }

        String iconKey = account.getIconKey();

        if (iconKey == null
                || iconKey.isBlank()
                || iconKey.length() > 64) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Icon key must contain between 1 and 64 characters"
            );
        }

        if (account.getAmount() != null
                && account.getAmount().stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Amount must have at most two decimal places"
            );
        }

        if (syncTombstoneRepository.accountWasDeleted(
                currentUser.getId(), account.getSyncId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Account was deleted and cannot be recreated with the same sync ID"
            );
        }

        if (accountRepository.existsBySyncId(account.getSyncId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Account sync ID is already in use"
            );
        }
        
        account.setUser(currentUser);

        if (account.getAmount() == null) {
            account.setAmount(BigDecimal.ZERO);
        }

        return accountRepository.save(account);
    }

    @Transactional
    public boolean deleteAccount(Long id, User currentUser) {
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

        if (hasEntries || hasTransfers) {
            return false;
        }

        accountRepository.deleteById(id);

        syncTombstoneRepository.recordAccountDeletion(
            currentUser.getId(), account.getSyncId()
        );

        return true;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccounts(User currentUser) {
        return accountRepository.findByUser(currentUser)
            .stream()
            .map(account -> new AccountResponse(
                account.getAccount_id(),
                account.getName(),
                account.getAmount(),
                account.getDescription(),
                account.getSyncId(),
                account.getVersion(),
                account.getCurrencyCode(),
                account.getIconKey(),
                account.getArchivedAt()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> getAccountSpent(User currentUser) {
        Map<Long, BigDecimal> spent = new HashMap<>();

        for (var total : entryRepository.sumAmountsByAccount(
                currentUser, EntryType.EXPENSE)) {
            spent.put(total.getAccountId(), total.getTotal());
        }

        return spent;
    }
}