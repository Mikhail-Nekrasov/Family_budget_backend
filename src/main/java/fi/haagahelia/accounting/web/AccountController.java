package fi.haagahelia.accounting.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;

@Controller
public class AccountController {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountService accountService;

    @GetMapping("/accounts")
    public String accounts(@RequestParam(value = "type", required = false) String type,
                           Model model,
                           Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new UsernameNotFoundException("User not found");
        }

    

        List<Account> accounts = accountRepository.findByUser(currentUser);

        Map<Long, BigDecimal> accountSpent = new HashMap<>();

        for (Account acc : accounts) {
            BigDecimal spent = BigDecimal.ZERO;

            if (acc.getEntries() != null) {
                for (Entry e : acc.getEntries()) {
                    if (e.getType() == EntryType.EXPENSE && e.getAmount() != null) {
                        spent = spent.add(e.getAmount());
                    }
                }
            }

            accountSpent.put(acc.getAccount_id(), spent);
        }

        // Total
        BigDecimal total = accounts.stream()
            .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        model.addAttribute("accounts", accounts);
        model.addAttribute("accountSpent", accountSpent);
        model.addAttribute("total", total);
        model.addAttribute("type", type);

        return "accounts";
    }

    @GetMapping("/addaccount")
    public String showAddAccountForm(Model model, Authentication auth) {
        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }


        Account account = new Account();
        account.setUser(currentUser); 

        model.addAttribute("account", account);
        return "addaccount";
    }

    @PostMapping("/addaccount")
    public String saveAccount(@ModelAttribute Account account,
                            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        accountService.createAccount(account, currentUser);

        return "redirect:/accounts";
    }

    @PostMapping("/deleteaccount/{id}")
    public String deleteAccount(@PathVariable("id") Long id,
                                Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        accountService.deleteAccount(id, currentUser);

        return "redirect:/accounts";
    }
}

