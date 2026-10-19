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

import fi.haagahelia.accounting.api.dto.CreateCategoryRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteCategoryRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.CategorySyncService;

@RestController
@RequestMapping("/api/sync/categories/{commandId}")
public class CategorySyncRestController {

    private final CategorySyncService categorySyncService;
    private final UserRepository userRepository;

    public CategorySyncRestController(
            CategorySyncService categorySyncService,
            UserRepository userRepository) {

        this.categorySyncService = categorySyncService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<String> createCategory(
            @PathVariable("commandId") UUID commandId,
            @RequestBody CreateCategoryRequest request,
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

        CompletedCommand result = categorySyncService.createCategory(
            commandId, request, currentUser
        );

        return ResponseEntity
            .status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
    }
    @PostMapping("/delete")
    public ResponseEntity<String> deleteCategory(
            @PathVariable("commandId") UUID commandId,
            @RequestBody SyncDeleteCategoryRequest request,
            Authentication auth) {
        User currentUser = userRepository.findByUsername(auth.getName());
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
        CompletedCommand result = categorySyncService.deleteCategory(
            commandId, request, currentUser
        );
        return ResponseEntity.status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }
}
