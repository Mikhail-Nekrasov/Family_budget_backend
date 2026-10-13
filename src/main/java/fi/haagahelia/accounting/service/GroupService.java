package fi.haagahelia.accounting.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.GroupResponse;
import fi.haagahelia.accounting.api.dto.GroupInvitationResponse;
import fi.haagahelia.accounting.api.dto.GroupMemberResponse;
import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.GroupInvitation;
import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.BudgetGroupRepository;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;
import fi.haagahelia.accounting.repository.GroupInvitationRepository;
import fi.haagahelia.accounting.repository.GroupMembershipRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@Service
public class GroupService {

    private final BudgetGroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final GroupAccountAccessRepository accountAccessRepository;

    public GroupService(BudgetGroupRepository groupRepository,
                        GroupMembershipRepository membershipRepository,
                        GroupInvitationRepository invitationRepository,
                        UserRepository userRepository,
                        GroupAccountAccessRepository accountAccessRepository) {
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.accountAccessRepository = accountAccessRepository;
    }

    @Transactional
    public GroupResponse createGroup(String name, User currentUser) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Group name is required"
            );
        }

        String normalizedName = name.strip();

        if (normalizedName.length() > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Group name must not exceed 100 characters"
            );
        }

        BudgetGroup group = groupRepository.save(
            new BudgetGroup(normalizedName, currentUser)
        );

        membershipRepository.save(
            new GroupMembership(group, currentUser)
        );

        return new GroupResponse(
            group.getId(),
            group.getName(),
            currentUser.getId(),
            group.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getGroups(User currentUser) {
        return groupRepository.findGroupsByMember(currentUser)
            .stream()
            .map(group -> new GroupResponse(
                group.getId(),
                group.getName(),
                group.getOwner().getId(),
                group.getCreatedAt()
            ))
            .toList();
    }

    private BudgetGroup requireMembership(Long groupId, User currentUser) {
        BudgetGroup group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Group not found"
            ));

        if (!membershipRepository.existsByGroup_IdAndUser(groupId, currentUser)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Group access denied"
            );
        }

        return group;
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(Long groupId, User currentUser) {
        BudgetGroup group = requireMembership(groupId, currentUser);

        return new GroupResponse(
            group.getId(),
            group.getName(),
            group.getOwner().getId(),
            group.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<GroupMemberResponse> getMembers(
            Long groupId, User currentUser) {

        BudgetGroup group = requireMembership(groupId, currentUser);
        Long ownerId = group.getOwner().getId();

        return membershipRepository
            .findByGroup_IdOrderByJoinedAtAscIdAsc(groupId)
            .stream()
            .map(membership -> new GroupMemberResponse(
                membership.getUser().getId(),
                membership.getUser().getUsername(),
                ownerId.equals(membership.getUser().getId()),
                membership.getJoinedAt()
            ))
            .toList();
    }

    @Transactional
    public GroupInvitationResponse inviteUser(
            Long groupId, String username, User currentUser) {

        BudgetGroup group = requireMembership(groupId, currentUser);

        if (!group.getOwner().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the group owner can invite users"
            );
        }

        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Username is required"
            );
        }

        User recipient = userRepository.findByUsername(username);

        if (recipient == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            );
        }

        if (membershipRepository.existsByGroup_IdAndUser(groupId, recipient)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "User is already a group member"
            );
        }

        if (invitationRepository.existsByGroup_IdAndInvitedUser(
                groupId, recipient)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "User already has an invitation to this group"
            );
        }

        GroupInvitation invitation = invitationRepository.save(
            new GroupInvitation(group, recipient, currentUser)
        );

        return new GroupInvitationResponse(
            invitation.getId(),
            group.getId(),
            group.getName(),
            recipient.getUsername(),
            currentUser.getUsername(),
            invitation.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<GroupInvitationResponse> getInvitations(User currentUser) {
        return invitationRepository
            .findByInvitedUserOrderByCreatedAtDescIdDesc(currentUser)
            .stream()
            .map(invitation -> new GroupInvitationResponse(
                invitation.getId(),
                invitation.getGroup().getId(),
                invitation.getGroup().getName(),
                invitation.getInvitedUser().getUsername(),
                invitation.getInvitedBy().getUsername(),
                invitation.getCreatedAt()
            ))
            .toList();
    }

    private GroupInvitation requireInvitationRecipient(
            Long invitationId, User currentUser) {

        GroupInvitation invitation = invitationRepository
            .findByIdForUpdate(invitationId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Invitation not found"
            ));

        if (!invitation.getInvitedUser().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Invitation access denied"
            );
        }

        return invitation;
    }

    @Transactional
    public GroupResponse acceptInvitation(
            Long invitationId, User currentUser) {

        GroupInvitation invitation = requireInvitationRecipient(
            invitationId, currentUser
        );

        BudgetGroup group = invitation.getGroup();

        if (!membershipRepository.existsByGroup_IdAndUser(
                group.getId(), currentUser)) {
            membershipRepository.save(
                new GroupMembership(group, currentUser)
            );
        }

        invitationRepository.delete(invitation);

        return new GroupResponse(
            group.getId(),
            group.getName(),
            group.getOwner().getId(),
            group.getCreatedAt()
        );
    }

    @Transactional
    public void declineInvitation(
            Long invitationId, User currentUser) {

        GroupInvitation invitation = requireInvitationRecipient(
            invitationId, currentUser
        );

        invitationRepository.delete(invitation);
    }

    private void removeMembership(Long groupId, User user) {
        accountAccessRepository.deleteSharedAccountsOfUser(groupId, user);
        membershipRepository.deleteByGroup_IdAndUser(groupId, user);
    }

    @Transactional
    public void leaveGroup(Long groupId, User currentUser) {
        BudgetGroup group = requireMembership(groupId, currentUser);

        if (group.getOwner().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The group owner cannot leave the group"
            );
        }

        removeMembership(groupId, currentUser);
    }

    @Transactional
    public void removeMember(
            Long groupId, Long userId, User currentUser) {

        BudgetGroup group = requireMembership(groupId, currentUser);

        if (!group.getOwner().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the group owner can remove members"
            );
        }

        if (group.getOwner().getId().equals(userId)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The group owner cannot be removed"
            );
        }

        User member = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User not found"
            ));

        removeMembership(groupId, member);
    }

    @Transactional
    public void deleteGroup(Long groupId, User currentUser) {
        BudgetGroup group = requireMembership(groupId, currentUser);

        if (!group.getOwner().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the group owner can delete the group"
            );
        }

        groupRepository.deleteGroupById(groupId);
    }
}