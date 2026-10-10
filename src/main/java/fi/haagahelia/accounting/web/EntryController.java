package fi.haagahelia.accounting.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.EntryService;
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

    @Autowired
    private EntryService entryService;

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

        // Calculate totals in the database
        BigDecimal total;

        if (type == null) {
            BigDecimal incomeSum = entryRepository.sumAmountByUserAndType(
                currentUser, EntryType.INCOME
            );

            BigDecimal expenseSum = entryRepository.sumAmountByUserAndType(
                currentUser, EntryType.EXPENSE
            );

            incomeSum = incomeSum != null ? incomeSum : BigDecimal.ZERO;
            expenseSum = expenseSum != null ? expenseSum : BigDecimal.ZERO;

            total = incomeSum.subtract(expenseSum);
        } else {
            BigDecimal sum = entryRepository.sumAmountByUserAndType(
                currentUser, type
            );

            total = sum != null ? sum : BigDecimal.ZERO;
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
    
    @PreAuthorize("@entrySecurity.isOwner(#id, authentication)")
    @PostMapping("/deleteentry/{id}")
    public String deleteEntry(
            @PathVariable("id") Long id,
            @RequestParam(value = "type", required = false) EntryType type,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        entryService.deleteEntry(id, currentUser);

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

        Entry updatedEntry = entryService.updateEntry(
            id, entryFromForm, currentUser
        );

        return "redirect:/entries?type=" + updatedEntry.getType();
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
                            @RequestParam(value = "type", required = false)
                            EntryType type,
                            Authentication auth,
                            HttpSession session) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        entryService.createEntry(entry, currentUser);

        String sessionKey = type != null
            ? "sessionNewEntry_" + type.name()
            : "sessionNewEntry";

        session.removeAttribute(sessionKey);

        if (type != null) {
            return "redirect:/entries?type=" + type;
        }

        return "redirect:/entries";
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
