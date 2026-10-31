package fi.haagahelia.accounting.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.UpdateAccountOrderRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.UserAccountOrderService;

@RestController
@RequestMapping("/api/sync/account-order/{commandId}")
public class AccountOrderSyncRestController {

    private final UserAccountOrderService orderService;
    private final UserRepository userRepository;

    public AccountOrderSyncRestController(
            UserAccountOrderService orderService,
            UserRepository userRepository) {

        this.orderService = orderService;
        this.userRepository = userRepository;
    }

    @PutMapping
    public ResponseEntity<String> saveOrder(
            @PathVariable("commandId") UUID commandId,
            @RequestBody UpdateAccountOrderRequest request,
            Authentication auth) {

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

        CompletedCommand result = orderService.saveOrder(
            commandId,
            request,
            currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }
}