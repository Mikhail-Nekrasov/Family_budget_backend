package fi.haagahelia.accounting.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateGroupInvitationRequest;
import fi.haagahelia.accounting.api.dto.CreateGroupRequest;
import fi.haagahelia.accounting.api.dto.GroupInvitationResponse;
import fi.haagahelia.accounting.api.dto.GroupMemberResponse;
import fi.haagahelia.accounting.api.dto.GroupResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.GroupService;

@RestController
@RequestMapping("/api/groups")
public class GroupRestController {

    private final GroupService groupService;
    private final UserRepository userRepository;

    public GroupRestController(GroupService groupService,
                               UserRepository userRepository) {
        this.groupService = groupService;
        this.userRepository = userRepository;
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

    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(
            @RequestBody CreateGroupRequest request,
            Authentication auth) {

        User currentUser = getCurrentUser(auth);

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        GroupResponse response = groupService.createGroup(
            request.name(), currentUser
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<GroupResponse> getGroups(Authentication auth) {
        User currentUser = getCurrentUser(auth);

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return groupService.getGroups(currentUser);
    }

    @GetMapping("/{id}")
    public GroupResponse getGroup(
            @PathVariable("id") Long id,
            Authentication auth) {

        return groupService.getGroup(id, getCurrentUser(auth));
    }

    @GetMapping("/{id}/members")
    public List<GroupMemberResponse> getMembers(
            @PathVariable("id") Long id,
            Authentication auth) {

        return groupService.getMembers(id, getCurrentUser(auth));
    }

    @PostMapping("/{id}/invitations")
    public ResponseEntity<GroupInvitationResponse> inviteUser(
            @PathVariable("id") Long id,
            @RequestBody CreateGroupInvitationRequest request,
            Authentication auth) {

        GroupInvitationResponse response = groupService.inviteUser(
            id, request.username(), getCurrentUser(auth)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveGroup(
            @PathVariable("id") Long id,
            Authentication auth) {

        groupService.leaveGroup(id, getCurrentUser(auth));

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable("groupId") Long groupId,
            @PathVariable("userId") Long userId,
            Authentication auth) {

        groupService.removeMember(
            groupId, userId, getCurrentUser(auth)
        );

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable("id") Long id,
            Authentication auth) {

        groupService.deleteGroup(id, getCurrentUser(auth));

        return ResponseEntity.noContent().build();
    }
}