package fi.haagahelia.accounting.web;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountBalanceService;
import fi.haagahelia.accounting.service.AccountService;

@Controller
public class AccountController {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountService accountService;
    private final AccountBalanceService accountBalanceService;

    public AccountController(AccountRepository accountRepository,
                            UserRepository userRepository,
                            AccountService accountService,
                            AccountBalanceService accountBalanceService
                        ) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accountService = accountService;
        this.accountBalanceService = accountBalanceService;
    }

    @GetMapping("/accounts")
    public String accounts(
            @RequestParam(value = "type", required = false) String type,
            Model model,
            Authentication auth,
            @RequestParam(value = "currency", defaultValue = "EUR")
            String currency) {

        User currentUser = userRepository.findByUsername(
            auth.getName()
        );

        if (currentUser == null) {
            throw new UsernameNotFoundException("User not found");
        }

        List<Account> accounts = accountRepository.findByUser(
            currentUser
        );

        Map<Long, BigDecimal> accountSpent = new HashMap<>();

        for (Account account : accounts) {
            accountSpent.put(
                account.getAccount_id(),
                BigDecimal.ZERO
            );
        }

        accountSpent.putAll(
            accountService.getAccountSpent(currentUser)
        );

        model.addAttribute("accounts", accounts);
        model.addAttribute("accountSpent", accountSpent);
        model.addAttribute("type", type);
        model.addAttribute("totalUnavailable", false);

        try {
            model.addAttribute(
                "totalBalance",
                accountBalanceService.getBalance(
                    currentUser,
                    currency
                )
            );
        } catch (ResponseStatusException exception) {
            if (!HttpStatus.SERVICE_UNAVAILABLE.equals(
                    exception.getStatusCode())) {
                throw exception;
            }

            model.addAttribute("totalUnavailable", true);
        }

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

