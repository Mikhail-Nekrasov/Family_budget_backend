package fi.haagahelia.accounting.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.SyncDownloadPage;
import fi.haagahelia.accounting.api.dto.SyncDownloadRequest;
import fi.haagahelia.accounting.api.dto.SyncDownloadResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.SyncDownloadService;

@RestController
@RequestMapping("/api/sync/downloads")
public class SyncDownloadRestController {
    private final SyncDownloadService service;
    private final UserRepository userRepository;

    public SyncDownloadRestController(SyncDownloadService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SyncDownloadResponse start(
            @RequestBody SyncDownloadRequest request, Authentication auth) {
        return service.start(request.cursor(), currentUser(auth));
    }

    @GetMapping("/{downloadId}")
    public SyncDownloadPage page(
            @PathVariable UUID downloadId,
            @RequestParam(defaultValue = "0") long after,
            Authentication auth) {
        return service.page(downloadId, after, currentUser(auth));
    }

    @PostMapping("/{downloadId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@PathVariable UUID downloadId, Authentication auth) {
        service.complete(downloadId, currentUser(auth));
    }

    private User currentUser(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
        return user;
    }
}
