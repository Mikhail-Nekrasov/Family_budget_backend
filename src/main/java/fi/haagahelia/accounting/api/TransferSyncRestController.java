package fi.haagahelia.accounting.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.SyncCreateTransferRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.TransferSyncService;

@RestController
@RequestMapping("/api/sync/transfers")
public class TransferSyncRestController {

    private final TransferSyncService transferSyncService;
    private final UserRepository userRepository;

    public TransferSyncRestController(
            TransferSyncService transferSyncService,
            UserRepository userRepository) {

        this.transferSyncService = transferSyncService;
        this.userRepository = userRepository;
    }

    @PostMapping("/{commandId}")
    public ResponseEntity<String> createTransfer(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncCreateTransferRequest request,
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

        CompletedCommand result = transferSyncService.createTransfer(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }
}