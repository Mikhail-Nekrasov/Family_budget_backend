package fi.haagahelia.accounting.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.ConvertedBalanceResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountBalanceService;

@RestController
@RequestMapping("/api/accounts/balance")
public class AccountBalanceRestController {

    private final AccountBalanceService accountBalanceService;
    private final UserRepository userRepository;

    public AccountBalanceRestController(
            AccountBalanceService accountBalanceService,
            UserRepository userRepository) {

        this.accountBalanceService = accountBalanceService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ConvertedBalanceResponse getBalance(
            @RequestParam(value = "currency", defaultValue = "EUR")
            String currency,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(
            auth.getName()
        );

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return accountBalanceService.getBalance(
            currentUser,
            currency
        );
    }
}