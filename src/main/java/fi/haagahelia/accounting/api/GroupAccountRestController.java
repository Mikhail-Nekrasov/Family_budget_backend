package fi.haagahelia.accounting.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.EntryResponse;
import fi.haagahelia.accounting.api.dto.GroupAccountResponse;
import fi.haagahelia.accounting.api.dto.PageResponse;
import fi.haagahelia.accounting.api.dto.SetGroupAccountAccessRequest;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.GroupAccountService;

@RestController
@RequestMapping("/api/groups/{groupId}/accounts")
public class GroupAccountRestController {

    private final GroupAccountService groupAccountService;
    private final UserRepository userRepository;

    public GroupAccountRestController(
            GroupAccountService groupAccountService,
            UserRepository userRepository) {
        this.groupAccountService = groupAccountService;
        this.userRepository = userRepository;
    }

    @PutMapping("/{accountId}")
    public GroupAccountResponse setAccess(
            @PathVariable("groupId") Long groupId,
            @PathVariable("accountId") Long accountId,
            @RequestBody SetGroupAccountAccessRequest request,
            Authentication auth) {

        return groupAccountService.setAccess(
            groupId,
            accountId,
            request.permission(),
            getCurrentUser(auth)
        );
    }

    private User getCurrentUser(Authentication auth) {
        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return currentUser;
    }

    @GetMapping
    public List<GroupAccountResponse> getAccounts(
            @PathVariable("groupId") Long groupId,
            Authentication auth) {

        return groupAccountService.getAccounts(
            groupId, getCurrentUser(auth)
        );
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> revokeAccess(
            @PathVariable("groupId") Long groupId,
            @PathVariable("accountId") Long accountId,
            Authentication auth) {

        groupAccountService.revokeAccess(
            groupId, accountId, getCurrentUser(auth)
        );

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{accountId}/entries")
    public PageResponse<EntryResponse> getEntries(
            @PathVariable("groupId") Long groupId,
            @PathVariable("accountId") Long accountId,
            @RequestParam(value = "type", required = false) EntryType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Authentication auth) {

        Page<EntryResponse> result = groupAccountService.getEntries(
            groupId, accountId, getCurrentUser(auth), type, page, size
        );

        return new PageResponse<>(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages()
        );
    }
}