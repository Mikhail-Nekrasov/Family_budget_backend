package fi.haagahelia.accounting.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteAccountRequest;
import fi.haagahelia.accounting.api.dto.SyncUpdateAccountRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.service.AccountSyncService;

@RestController
@RequestMapping("/api/sync/accounts/{commandId}")
public class AccountSyncRestController {

    private final AccountSyncService accountSyncService;
    private final UserRepository userRepository;

    public AccountSyncRestController(
            AccountSyncService accountSyncService,
            UserRepository userRepository) {

        this.accountSyncService = accountSyncService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<String> createAccount(
            @PathVariable("commandId") UUID commandId,
            @RequestBody CreateAccountRequest request,
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

        CompletedCommand result = accountSyncService.createAccount(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }

    @PutMapping
    public ResponseEntity<String> updateAccount(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncUpdateAccountRequest request,
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

        CompletedCommand result = accountSyncService.updateAccount(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }

    @PostMapping("/delete")
    public ResponseEntity<String> deleteAccount(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncDeleteAccountRequest request,
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

        CompletedCommand result = accountSyncService.deleteAccount(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }
}