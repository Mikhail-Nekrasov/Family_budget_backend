package fi.haagahelia.accounting.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class EntryController {

    @RequestMapping({"/", "index"})
    public String hello() {
        return "index";
    }

    @Autowired
    private EntryRepository entryRepository;

    @Autowired
    public CategoryRepository categoryRepository;

    @Autowired
    public AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/entries")
    public String getEntries(
            @RequestParam(value = "type", required = false) EntryType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "5") int size,
            Model model,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());
        
        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }  

        // Sorting by date
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dateTime"));

        Page<Entry> entryPage;

        if (type == null) {
            entryPage = entryRepository.findByUser(currentUser, pageable);
        } else {
            entryPage = entryRepository.findByUserAndType(currentUser, type, pageable);
        }

        List<Entry> entries = entryPage.getContent();

        // Calculating Total
        BigDecimal total;
        if (type == null) {
            List<Entry> allEntries = entryRepository.findByUser(currentUser);
            BigDecimal incomeSum = allEntries.stream()
                    .filter(e -> e.getType() == EntryType.INCOME)
                    .map(Entry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expenseSum = allEntries.stream()
                    .filter(e -> e.getType() == EntryType.EXPENSE)
                    .map(Entry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            total = incomeSum.subtract(expenseSum);
        } else {
            List<Entry> allEntriesByType = entryRepository.findByUserAndType(currentUser, type);
            total = allEntriesByType.stream()
                    .map(Entry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        // Page Title
        String pageTitle = (type == null)
                ? "All entries"
                : (type == EntryType.EXPENSE ? "Expenses" : "Incomes");

        // Data for a template
        model.addAttribute("entries", entries);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("type", type);
        model.addAttribute("total", total);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", entryPage.getTotalPages());

        return "entries";
    }
    
    @Transactional
    @PreAuthorize("@entrySecurity.isOwner(#id, authentication)")
    @PostMapping("/deleteentry/{id}")
    public String deleteEntry(@PathVariable("id") Long id,
                              @RequestParam(value = "type", required = false) EntryType type,
                              Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }
        
        Entry entry = entryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid entry Id:" + id));

        // Added for new logic (amount is the Account property)          
        Account account = entry.getAccount();
        BigDecimal currentAmount = account.getAmount() != null ? account.getAmount() : BigDecimal.ZERO;

        // If we delete an Expense, Account's amount should increase and vice versa
        if (entry.getType() == EntryType.EXPENSE) {
            currentAmount = currentAmount.add(entry.getAmount());
        } else if (entry.getType() == EntryType.INCOME) {
            currentAmount = currentAmount.subtract(entry.getAmount());
        }

        account.setAmount(currentAmount);
        accountRepository.save(account);

        entryRepository.delete(entry);

        if (type != null) {
            return "redirect:/entries?type=" + type;
        }
        return "redirect:/entries";
    }

    @Transactional
    @PreAuthorize("@entrySecurity.isOwner(#id, authentication)")
    @GetMapping("/editentry/{id}")
    public String editEntry(@PathVariable("id") Long id,
                            Model model,
                            Authentication auth,
                            HttpSession session) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }
        
        Entry entry = entryRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Invalid entry Id:" + id));
        
        Entry sessionEntry = (Entry) session.getAttribute("sessionEditEntry_" + id);
        if (sessionEntry != null) {
            entry = sessionEntry;
            session.removeAttribute("sessionEditEntry_" + id);
        }

        if (entry.getDateTime() == null) {
            entry.setDateTime(LocalDateTime.now());
        }
        
        model.addAttribute("entry", entry);
        
        // Show personal and global categories of the entry's type
        model.addAttribute(
            "categories",
            categoryRepository.findByTypeAndUserOrTypeAndUserIsNull(
                entry.getType(), currentUser, entry.getType()
            )
        );

        model.addAttribute("accounts", accountRepository.findByUser(currentUser));
        model.addAttribute("type", entry.getType());

        model.addAttribute("currentUrl", "/editentry/" + id);

        //String referer = request.getHeader("referer");
        //model.addAttribute("returnUrl", referer);

        return "editentry";
    }

    @Transactional
    @PreAuthorize("@entrySecurity.isOwner(#id, authentication)")
    @PostMapping("/editentry/{id}")
    public String saveEditedEntry(@PathVariable("id") Long id,
                                  @ModelAttribute Entry entryFromForm,
                                  Authentication auth,
                                  HttpSession session) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }
                
        Entry existingEntry = entryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Invalid entry Id:" + id));

        validateEntryReferences(entryFromForm, currentUser);

        // Added for new logic (amount is the Account property)    
        // Reverse the original entry on the old account - NEW
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

        // Use one object when the account has not changed
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

        return "redirect:/entries?type=" + existingEntry.getType();
    }

    @GetMapping("/addentry")
    public String addEntry(@RequestParam(value = "type", required = false) EntryType type,
                           Model model,
                           HttpSession session,
                           Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        String sessionKey = type != null ? "sessionNewEntry_" + type.name() : "sessionNewEntry";

        Entry entry = (Entry) session.getAttribute(sessionKey);

        if (entry == null) {
            entry = new Entry();
            entry.setDateTime(LocalDateTime.now());
            if (type != null) entry.setType(type);
        } else {
            session.removeAttribute(sessionKey);
        }

        model.addAttribute("entry", entry);

        // Show personal and global categories only
        model.addAttribute(
            "categories",
            type != null
                ? categoryRepository.findByTypeAndUserOrTypeAndUserIsNull(
                    type, currentUser, type
                )
                : categoryRepository.findByUserOrUserIsNull(currentUser)
        );

        model.addAttribute("accounts", accountRepository.findByUser(currentUser));
        model.addAttribute("type", type);

        model.addAttribute("currentUrl", "/addentry" + (type != null ? "?type=" + type : ""));

        return "addentry";
    }

    @PostMapping("/addentry")
    public String saveNewEntry(@ModelAttribute Entry entry,
                               @RequestParam(value = "type", required = false) EntryType type,
                               Authentication auth,
                               HttpSession session) {
        
        User currentUser = userRepository.findByUsername(auth.getName());
        
        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }
        
        validateEntryReferences(entry, currentUser);
        entry.setUser(currentUser);

        // Added for new logic (amount is the Account property)  
        Account account = entry.getAccount();
        BigDecimal currentAmount = account.getAmount() != null ? account.getAmount() : BigDecimal.ZERO;

        if (entry.getType() == EntryType.INCOME) {
            account.setAmount(currentAmount.add(entry.getAmount()));
        } else if (entry.getType() == EntryType.EXPENSE) {
            account.setAmount(currentAmount.subtract(entry.getAmount()));
        }

        accountRepository.save(account);
        entryRepository.save(entry);

        String sessionKey = type != null ? "sessionNewEntry_" + type.name() : "sessionNewEntry";
        session.removeAttribute(sessionKey);
        
        if (type != null) {
            return "redirect:/entries?type=" + type;
        }
        return "redirect:/entries";
    }

    private void validateEntryReferences(Entry entry, User currentUser) {
        
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

    public String saveNewEntrySession(@ModelAttribute Entry entry,
                                      @RequestParam("returnUrl") String returnUrl,
                                      HttpSession session) {

        String sessionKey = entry.getType() != null
                ? "sessionNewEntry_" + entry.getType().name()
                : "sessionNewEntry";

        session.setAttribute(sessionKey, entry);

        return "redirect:" + returnUrl;
    }

}
