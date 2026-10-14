package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.GroupInvitation;
import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;

class GroupInvitationApiIntegrationTest
    extends AbstractGroupApiIntegrationTest {

    private GroupInvitation createIncomingInvitation() {
        User inviter = new User();
        inviter.setUsername("invitation_action_owner");
        inviter.setPassword("unused");
        inviter.setRoles(Set.of());
        inviter = userRepository.saveAndFlush(inviter);

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Invitation action group", inviter)
        );

        membershipRepository.save(
            new GroupMembership(group, inviter)
        );

        return invitationRepository.saveAndFlush(
            new GroupInvitation(group, owner, inviter)
        );
    }

    @Test
    void ownerCanInviteUserWithoutAddingMembership() throws Exception {
        User recipient = new User();
        recipient.setUsername("group_invitation_recipient");
        recipient.setPassword("unused");
        recipient.setRoles(Set.of());
        recipient = userRepository.saveAndFlush(recipient);

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Invitation group", owner)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, owner)
        );

        mockMvc.perform(post("/api/groups/{id}/invitations", group.getId())
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "username": "group_invitation_recipient"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.groupName").value("Invitation group"))
            .andExpect(jsonPath("$.invitedUsername")
                .value("group_invitation_recipient"));

        invitationRepository.flush();

        assertEquals(1, invitationRepository.count());
        assertEquals(1, membershipRepository.count());
    }

    @Test
    void ordinaryMemberCannotInviteUsers() throws Exception {
        User other = new User();
        other.setUsername("invitation_group_owner");
        other.setPassword("unused");
        other.setRoles(Set.of());
        other = userRepository.saveAndFlush(other);

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Owner managed group", other)
        );

        membershipRepository.save(new GroupMembership(group, other));
        membershipRepository.saveAndFlush(new GroupMembership(group, owner));

        mockMvc.perform(post("/api/groups/{id}/invitations", group.getId())
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "username": "someone"
                    }
                    """))
            .andExpect(status().isForbidden());

        assertEquals(0, invitationRepository.count());
    }

    @Test
    void returnsOnlyInvitationsAddressedToCurrentUser() throws Exception {
        User other = new User();
        other.setUsername("incoming_invitation_other");
        other.setPassword("unused");
        other.setRoles(Set.of());
        other = userRepository.saveAndFlush(other);

        BudgetGroup incomingGroup = groupRepository.save(
            new BudgetGroup("Incoming group", other)
        );
        membershipRepository.save(
            new GroupMembership(incomingGroup, other)
        );
        invitationRepository.save(
            new GroupInvitation(incomingGroup, owner, other)
        );

        BudgetGroup outgoingGroup = groupRepository.save(
            new BudgetGroup("Outgoing group", owner)
        );
        membershipRepository.save(
            new GroupMembership(outgoingGroup, owner)
        );
        invitationRepository.saveAndFlush(
            new GroupInvitation(outgoingGroup, other, owner)
        );

        mockMvc.perform(get("/api/group-invitations")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].groupName").value("Incoming group"))
            .andExpect(jsonPath("$[0].invitedUsername")
                .value(owner.getUsername()))
            .andExpect(jsonPath("$[0].invitedByUsername")
                .value(other.getUsername()));
    }

    @Test
    void recipientCanAcceptInvitation() throws Exception {
        GroupInvitation invitation = createIncomingInvitation();
        Long groupId = invitation.getGroup().getId();

        mockMvc.perform(post(
                    "/api/group-invitations/{id}/accept",
                    invitation.getId()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(groupId.intValue()));

        membershipRepository.flush();

        assertTrue(
            membershipRepository.existsByGroup_IdAndUser(groupId, owner)
        );
        assertFalse(
            invitationRepository.existsById(invitation.getId())
        );
    }

    @Test
    void recipientCanDeclineInvitation() throws Exception {
        GroupInvitation invitation = createIncomingInvitation();
        Long groupId = invitation.getGroup().getId();

        mockMvc.perform(post(
                    "/api/group-invitations/{id}/decline",
                    invitation.getId()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        invitationRepository.flush();

        assertFalse(
            membershipRepository.existsByGroup_IdAndUser(groupId, owner)
        );
        assertFalse(
            invitationRepository.existsById(invitation.getId())
        );
    }

    @Test
    void anotherUserCannotAcceptOrDeclineInvitation() throws Exception {
        GroupInvitation invitation = createIncomingInvitation();
        User inviter = invitation.getInvitedBy();

        mockMvc.perform(post(
                    "/api/group-invitations/{id}/accept",
                    invitation.getId()
                )
                .with(user(inviter.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        mockMvc.perform(post(
                    "/api/group-invitations/{id}/decline",
                    invitation.getId()
                )
                .with(user(inviter.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(invitationRepository.existsById(invitation.getId()));
        assertFalse(
            membershipRepository.existsByGroup_IdAndUser(
                invitation.getGroup().getId(), owner
            )
        );
    }
}