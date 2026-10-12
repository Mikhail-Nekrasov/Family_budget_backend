package fi.haagahelia.accounting.service;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateEntryRequest;
import fi.haagahelia.accounting.api.dto.EntryResponse;
import fi.haagahelia.accounting.api.dto.EntrySummaryResponse;
import fi.haagahelia.accounting.api.dto.UpdateEntryRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;

@Service
public class EntryService {

    private final EntryRepository entryRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    public EntryService(EntryRepository entryRepository,
                        AccountRepository accountRepository,
                        CategoryRepository categoryRepository) {
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public void createEntry(Entry entry, User currentUser) {
        if (entry.getId() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "ID must not be supplied when creating an entry"
        );
    }    
        validateEntryReferences(entry, currentUser);
        entry.setUser(currentUser);

        Account account = entry.getAccount();
        BigDecimal balance = account.getAmount() != null
            ? account.getAmount()
            : BigDecimal.ZERO;

        if (entry.getType() == EntryType.INCOME) {
            account.setAmount(balance.add(entry.getAmount()));
        } else if (entry.getType() == EntryType.EXPENSE) {
            account.setAmount(balance.subtract(entry.getAmount()));
        }

        accountRepository.save(account);
        entryRepository.save(entry);
    }

    private void validateEntryReferences(Entry entry, User currentUser) {
        
        // Require a non-blank title and an operation date
        if (entry.getTitle() == null || entry.getTitle().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Title is required"
            );
        }

        if (entry.getDateTime() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Date and time are required"
            );
        }

        // Require a positive amount for both incomes and expenses
            if (entry.getAmount() == null
                    || entry.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount must be greater than zero"
                );
            }

        if (entry.getAccount() == null || entry.getAccount().getAccount_id() == null
                || entry.getCategory() == null || entry.getCategory().getCategory_id() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account and category are required");
        }

        // Reload from the database: ownership supplied by the client is not trusted.
        Account account = accountRepository.findById(entry.getAccount().getAccount_id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account not found"));
        Category category = categoryRepository.findById(entry.getCategory().getCategory_id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));

        if (account.getUser() == null || !currentUser.getId().equals(account.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account access denied");
        }
        if (!category.isGlobal() && !currentUser.getId().equals(category.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Category access denied");
        }
        if (entry.getType() == null || category.getType() != entry.getType()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type does not match entry type");
        }

        entry.setAccount(account);
        entry.setCategory(category);
    }

    @Transactional
    public Entry updateEntry(Long id, Entry entryFromForm, User currentUser) {
        Entry existingEntry = entryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Entry not found"
            ));

        if (existingEntry.getUser() == null
                || !currentUser.getId().equals(existingEntry.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Entry access denied"
            );
        }

        validateEntryReferences(entryFromForm, currentUser);

        // Reverse the original entry on the old account
        Account oldAccount = existingEntry.getAccount();
        BigDecimal oldBalance = oldAccount.getAmount() != null
            ? oldAccount.getAmount()
            : BigDecimal.ZERO;

        if (existingEntry.getType() == EntryType.INCOME) {
            oldBalance = oldBalance.subtract(existingEntry.getAmount());
        } else if (existingEntry.getType() == EntryType.EXPENSE) {
            oldBalance = oldBalance.add(existingEntry.getAmount());
        }

        // Apply the edited entry to the selected account
        Account selectedAccount = entryFromForm.getAccount();

        boolean sameAccount = oldAccount.getAccount_id()
            .equals(selectedAccount.getAccount_id());

        Account newAccount = sameAccount ? oldAccount : selectedAccount;

        BigDecimal newBalance = sameAccount
            ? oldBalance
            : (newAccount.getAmount() != null
                ? newAccount.getAmount()
                : BigDecimal.ZERO);

        if (entryFromForm.getType() == EntryType.INCOME) {
            newBalance = newBalance.add(entryFromForm.getAmount());
        } else if (entryFromForm.getType() == EntryType.EXPENSE) {
            newBalance = newBalance.subtract(entryFromForm.getAmount());
        }

        if (!sameAccount) {
            oldAccount.setAmount(oldBalance);
            accountRepository.save(oldAccount);
        }

        newAccount.setAmount(newBalance);
        accountRepository.save(newAccount);

        existingEntry.setTitle(entryFromForm.getTitle());
        existingEntry.setAmount(entryFromForm.getAmount());
        existingEntry.setType(entryFromForm.getType());
        existingEntry.setDateTime(entryFromForm.getDateTime());
        existingEntry.setDescription(entryFromForm.getDescription());
        existingEntry.setCategory(entryFromForm.getCategory());
        existingEntry.setAccount(newAccount);

        entryRepository.save(existingEntry);

        return existingEntry;
    }

    @Transactional
    public void deleteEntry(Long id, User currentUser) {
        Entry entry = entryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Entry not found"
            ));

        if (entry.getUser() == null
                || !currentUser.getId().equals(entry.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Entry access denied"
            );
        }

        Account account = entry.getAccount();
        BigDecimal balance = account.getAmount() != null
            ? account.getAmount()
            : BigDecimal.ZERO;

        // Reverse the deleted entry's effect on the balance
        if (entry.getType() == EntryType.EXPENSE) {
            balance = balance.add(entry.getAmount());
        } else if (entry.getType() == EntryType.INCOME) {
            balance = balance.subtract(entry.getAmount());
        }

        account.setAmount(balance);
        accountRepository.save(account);

        entryRepository.delete(entry);
    }

    @Transactional(readOnly = true)
    public Page<EntryResponse> getEntries(
            User currentUser, EntryType type, int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be non-negative and size must be between 1 and 100"
            );
        }

        Pageable pageable = PageRequest.of(
            page,
            size,
            Sort.by(
                Sort.Order.desc("dateTime"),
                Sort.Order.desc("id")
            )
        );

        Page<Entry> entries = type == null
            ? entryRepository.findByUser(currentUser, pageable)
            : entryRepository.findByUserAndType(currentUser, type, pageable);

        return entries.map(entry -> new EntryResponse(
            entry.getId(),
            entry.getTitle(),
            entry.getType(),
            entry.getAmount(),
            entry.getDateTime(),
            entry.getDescription(),
            entry.getAccount().getAccount_id(),
            entry.getAccount().getName(),
            entry.getCategory().getCategory_id(),
            entry.getCategory().getName()
        ));
    }

    @Transactional
    public EntryResponse createEntryFromRequest(
            CreateEntryRequest request, User currentUser) {

        if (request.accountId() == null || request.categoryId() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account and category are required"
            );
        }

        Account account = accountRepository.findById(request.accountId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account not found"
            ));

        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Category not found"
            ));

        Entry entry = new Entry(
            request.title(),
            request.type(),
            request.amount(),
            request.dateTime(),
            request.description(),
            category,
            account,
            currentUser
        );

        createEntry(entry, currentUser);

        return new EntryResponse(
            entry.getId(),
            entry.getTitle(),
            entry.getType(),
            entry.getAmount(),
            entry.getDateTime(),
            entry.getDescription(),
            entry.getAccount().getAccount_id(),
            entry.getAccount().getName(),
            entry.getCategory().getCategory_id(),
            entry.getCategory().getName()
        );
    }

    @Transactional
    public EntryResponse updateEntryFromRequest(
            Long id, UpdateEntryRequest request, User currentUser) {

        if (request.accountId() == null || request.categoryId() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account and category are required"
            );
        }

        Account account = accountRepository.findById(request.accountId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Account not found"
            ));

        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Category not found"
            ));

        Entry editedEntry = new Entry(
            request.title(),
            request.type(),
            request.amount(),
            request.dateTime(),
            request.description(),
            category,
            account,
            currentUser
        );

        Entry updated = updateEntry(id, editedEntry, currentUser);

        return new EntryResponse(
            updated.getId(),
            updated.getTitle(),
            updated.getType(),
            updated.getAmount(),
            updated.getDateTime(),
            updated.getDescription(),
            updated.getAccount().getAccount_id(),
            updated.getAccount().getName(),
            updated.getCategory().getCategory_id(),
            updated.getCategory().getName()
        );
    }

    @Transactional(readOnly = true)
    public EntrySummaryResponse getSummary(User currentUser) {
        BigDecimal income = entryRepository.sumAmountByUserAndType(
            currentUser, EntryType.INCOME
        );

        BigDecimal expense = entryRepository.sumAmountByUserAndType(
            currentUser, EntryType.EXPENSE
        );

        income = income != null ? income : BigDecimal.ZERO;
        expense = expense != null ? expense : BigDecimal.ZERO;

        return new EntrySummaryResponse(
            income,
            expense,
            income.subtract(expense)
        );
    }
}