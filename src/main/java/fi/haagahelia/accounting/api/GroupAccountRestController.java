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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/groups/{groupId}/accounts")
@Tag(
    name = "Group accounts",
    description = "Accounts explicitly shared with a group"
)
public class GroupAccountRestController {

    private final GroupAccountService groupAccountService;
    private final UserRepository userRepository;

    public GroupAccountRestController(
            GroupAccountService groupAccountService,
            UserRepository userRepository) {
        this.groupAccountService = groupAccountService;
        this.userRepository = userRepository;
    }

    @Operation(
        summary = "Share an account or change its permission",
        description = """
            Only the account owner can manage sharing.
            The owner must be a member of the group.

            READ allows viewing the account and its complete entry history,
            and receiving transfers into the account.

            EDIT additionally allows creating, editing and deleting entries,
            and withdrawing transfers from the account.

            Sharing does not grant permission to delete the account.
            """
    )
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

    @Operation(
        summary = "List accounts shared with a group",
        description = """
            Available only to group members.
            Returns explicitly shared accounts whose owners
            are still members of the group.
            Private accounts are excluded.
            """
    )
    @GetMapping
    public List<GroupAccountResponse> getAccounts(
            @PathVariable("groupId") Long groupId,
            Authentication auth) {

        return groupAccountService.getAccounts(
            groupId, getCurrentUser(auth)
        );
    }

    @Operation(
        summary = "Stop sharing an account with a group",
        description = """
            Only the account owner can revoke sharing.
            The account and its financial records are preserved.
            Revoking an already absent sharing permission is allowed.

            Access granted through other groups remains effective.
            """
    )
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

    @Operation(
        summary = "Read the entry history of a shared account",
        description = """
            Requires membership in this group and account sharing
            with this group. Both READ and EDIT allow viewing entries.

            Includes entries created before the account was shared.
            Supports filtering by entry type and pagination.
            Page starts at 0; size must be between 1 and 100.

            Create, edit and delete entries through /api/entries.
            These operations require EDIT permission on the account.
            """
    )
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