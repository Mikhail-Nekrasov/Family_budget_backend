package fi.haagahelia.accounting.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.AccountResponse;
import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;

@RestController
@RequestMapping("/api/accounts")
public class AccountRestController {

    private final AccountService accountService;
    private final UserRepository userRepository;

    public AccountRestController(AccountService accountService,
                                 UserRepository userRepository) {
        this.accountService = accountService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<AccountResponse> getAccounts(Authentication auth) {
        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return accountService.getAccounts(currentUser);
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @RequestBody CreateAccountRequest request,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        Account account = new Account(
            request.name(),
            request.amount(),
            request.description(),
            currentUser
        );

        account.setCurrencyCode(request.currencyCode());
        account.setIconKey(request.iconKey());

        if (request.syncId() != null) {
            account.assignSyncId(request.syncId());
        }

        Account saved = accountService.createAccount(account, currentUser);

        AccountResponse response = new AccountResponse(
            saved.getAccount_id(),
            saved.getName(),
            saved.getAmount(),
            saved.getDescription(),
            saved.getSyncId(),
            saved.getVersion(),
            saved.getCurrencyCode(),
            saved.getIconKey(),
            saved.getArchivedAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(
            @PathVariable("id") Long id,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        boolean deleted = accountService.deleteAccount(id, currentUser);

        if (!deleted) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Account has entries or transfers"
            );
        }

        return ResponseEntity.noContent().build();
    }
}