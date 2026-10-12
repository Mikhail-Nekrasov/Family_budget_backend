package fi.haagahelia.accounting.api;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateEntryRequest;
import fi.haagahelia.accounting.api.dto.EntryResponse;
import fi.haagahelia.accounting.api.dto.EntrySummaryResponse;
import fi.haagahelia.accounting.api.dto.PageResponse;
import fi.haagahelia.accounting.api.dto.UpdateEntryRequest;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.EntryService;

@RestController
@RequestMapping("/api/entries")
public class EntryRestController {

    private final EntryService entryService;
    private final UserRepository userRepository;

    public EntryRestController(EntryService entryService,
                               UserRepository userRepository) {
        this.entryService = entryService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public PageResponse<EntryResponse> getEntries(
            @RequestParam(value = "type", required = false) EntryType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        Page<EntryResponse> result = entryService.getEntries(
            currentUser, type, page, size
        );

        return new PageResponse<>(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages()
        );
    }

    @PostMapping
    public ResponseEntity<EntryResponse> createEntry(
            @RequestBody CreateEntryRequest request,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        EntryResponse response = entryService.createEntryFromRequest(
            request, currentUser
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public EntryResponse updateEntry(
            @PathVariable("id") Long id,
            @RequestBody UpdateEntryRequest request,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return entryService.updateEntryFromRequest(
            id, request, currentUser
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(
            @PathVariable("id") Long id,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        entryService.deleteEntry(id, currentUser);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public EntrySummaryResponse getSummary(Authentication auth) {
        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return entryService.getSummary(currentUser);
    }
}