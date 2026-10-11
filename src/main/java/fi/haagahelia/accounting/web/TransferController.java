package fi.haagahelia.accounting.web;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.TransferService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class TransferController {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransferService transferService;

    @GetMapping("/transfer/{toId}")
    public String showTransferForm(@PathVariable Long toId, 
                                   Model model,
                                   Authentication auth,
                                   @RequestParam(value = "error", required = false) String error) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        Account toAccount = accountRepository.findById(toId).orElse(null);

        if (toAccount == null
                || toAccount.getUser() == null
                || !currentUser.getId().equals(toAccount.getUser().getId())) {
            return "redirect:/accounts";
        }

        List<Account> accounts = accountRepository.findByUser(currentUser);

        Map<Long, BigDecimal> balances = new HashMap<>();
        for (Account acc : accounts) {
            balances.put(acc.getAccount_id(), acc.getAmount());
        }

        model.addAttribute("accounts", accounts);
        model.addAttribute("toAccount", toAccount);
        model.addAttribute("balances", balances);
        model.addAttribute("error", error);

        return "transfer";
    }

    @PostMapping("/transfer")
    public String executeTransfer(@RequestParam Long fromId,
                                @RequestParam Long toId,
                                @RequestParam BigDecimal amount,
                                @RequestParam(required = false) String description,
                                Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        TransferService.TransferResult result = transferService.executeTransfer(
            fromId, toId, amount, description, currentUser
        );

        return switch (result) {
            case SUCCESS -> "redirect:/transfers";
            case INVALID_ACCOUNTS -> "redirect:/accounts";
            case SAME_ACCOUNT -> "redirect:/transfer/" + toId + "?error=same";
            case INVALID_AMOUNT -> "redirect:/transfer/" + toId + "?error=invalid";
            case INSUFFICIENT_FUNDS -> "redirect:/transfer/" + toId + "?error=nomoney";
        };
    }

    @GetMapping("/transfers")
    public String listTransfers(Model model, Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());
        
        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }
        
        List<Transfer> transfers =
            transferRepository.findVisibleToUser(currentUser);

        model.addAttribute("transfers", transfers);
        return "transfers";
    }
}
