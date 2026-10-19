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

import fi.haagahelia.accounting.api.dto.SyncCreateEntryRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteEntryRequest;
import fi.haagahelia.accounting.api.dto.SyncUpdateEntryRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.EntrySyncService;

@RestController
@RequestMapping("/api/sync/entries/{commandId}")
public class EntrySyncRestController {

    private final EntrySyncService entrySyncService;
    private final UserRepository userRepository;

    public EntrySyncRestController(
            EntrySyncService entrySyncService,
            UserRepository userRepository) {

        this.entrySyncService = entrySyncService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<String> createEntry(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncCreateEntryRequest request,
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

        CompletedCommand result = entrySyncService.createEntry(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }

    @PutMapping
    public ResponseEntity<String> updateEntry(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncUpdateEntryRequest request,
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

        CompletedCommand result = entrySyncService.updateEntry(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }

    @PostMapping("/delete")
    public ResponseEntity<String> deleteEntry(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncDeleteEntryRequest request,
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

        CompletedCommand result = entrySyncService.deleteEntry(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }
}