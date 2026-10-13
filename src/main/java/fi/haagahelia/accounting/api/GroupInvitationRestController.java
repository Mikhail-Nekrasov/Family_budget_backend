package fi.haagahelia.accounting.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.GroupInvitationResponse;
import fi.haagahelia.accounting.api.dto.GroupResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.GroupService;

@RestController
@RequestMapping("/api/group-invitations")
public class GroupInvitationRestController {

    private final GroupService groupService;
    private final UserRepository userRepository;

    public GroupInvitationRestController(GroupService groupService,
                                         UserRepository userRepository) {
        this.groupService = groupService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<GroupInvitationResponse> getInvitations(Authentication auth) {
        return groupService.getInvitations(getCurrentUser(auth));
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

    @PostMapping("/{id}/accept")
    public GroupResponse acceptInvitation(
            @PathVariable("id") Long id,
            Authentication auth) {

        return groupService.acceptInvitation(id, getCurrentUser(auth));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Void> declineInvitation(
            @PathVariable("id") Long id,
            Authentication auth) {

        groupService.declineInvitation(id, getCurrentUser(auth));

        return ResponseEntity.noContent().build();
    }
}