package fi.haagahelia.accounting.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.AccountOrderResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.UserAccountOrderService;

@RestController
@RequestMapping("/api/account-order")
public class AccountOrderRestController {

    private final UserAccountOrderService orderService;
    private final UserRepository userRepository;

    public AccountOrderRestController(
            UserAccountOrderService orderService,
            UserRepository userRepository) {

        this.orderService = orderService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public AccountOrderResponse getOrder(Authentication auth) {
        if (auth == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required"
            );
        }

        User currentUser = userRepository.findByUsername(
            auth.getName()
        );

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return orderService.getOrder(currentUser);
    }
}