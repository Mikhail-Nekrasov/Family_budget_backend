package fi.haagahelia.accounting.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.GroupAccountAccess;
import fi.haagahelia.accounting.model.GroupInvitation;
import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;

class GroupApiIntegrationTest
    extends AbstractGroupApiIntegrationTest {

    @Test
    void createsGroupAndOwnerMembership() throws Exception {
        mockMvc.perform(post("/api/groups")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "  Trip to Italy  "
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Trip to Italy"))
            .andExpect(jsonPath("$.ownerId").value(owner.getId().intValue()));

        groupRepository.flush();

        var groups = groupRepository.findAll();
        var memberships = membershipRepository.findAll();

        assertEquals(1, groups.size());
        assertEquals(owner.getId(), groups.get(0).getOwner().getId());

        assertEquals(1, memberships.size());
        assertEquals(owner.getId(), memberships.get(0).getUser().getId());
        assertEquals(
            groups.get(0).getId(),
            memberships.get(0).getGroup().getId()
        );
    }

    @Test
    void rejectsBlankNameWithoutCreatingGroup() throws Exception {
        mockMvc.perform(post("/api/groups")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "   "
                    }
                    """))
            .andExpect(status().isBadRequest());

        assertEquals(0, groupRepository.count());
        assertEquals(0, membershipRepository.count());
    }

    @Test
    void returnsOwnedAndJoinedGroupsButNotUnrelatedGroups() throws Exception {
        User other = new User();
        other.setUsername("group_api_other");
        other.setPassword("unused");
        other.setRoles(Set.of());
        other = userRepository.saveAndFlush(other);

        BudgetGroup ownGroup = groupRepository.save(
            new BudgetGroup("Own group", owner)
        );
        membershipRepository.save(
            new GroupMembership(ownGroup, owner)
        );

        BudgetGroup joinedGroup = groupRepository.save(
            new BudgetGroup("Joined group", other)
        );
        membershipRepository.save(
            new GroupMembership(joinedGroup, other)
        );
        membershipRepository.save(
            new GroupMembership(joinedGroup, owner)
        );

        BudgetGroup unrelatedGroup = groupRepository.save(
            new BudgetGroup("Unrelated group", other)
        );
        membershipRepository.save(
            new GroupMembership(unrelatedGroup, other)
        );

        membershipRepository.flush();

        mockMvc.perform(get("/api/groups")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].name", hasItem("Own group")))
            .andExpect(jsonPath("$[*].name", hasItem("Joined group")))
            .andExpect(jsonPath(
                "$[*].name", not(hasItem("Unrelated group"))
            ));
    }

    @Test
    void returnsEmptyListWhenUserHasNoGroups() throws Exception {
        mockMvc.perform(get("/api/groups")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }

    @Test
    void memberCanReadGroupAndMembers() throws Exception {
        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Visible group", owner)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, owner)
        );

        mockMvc.perform(get("/api/groups/{id}", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Visible group"));

        mockMvc.perform(get("/api/groups/{id}/members", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].username").value(owner.getUsername()))
            .andExpect(jsonPath("$[0].owner").value(true))
            .andExpect(jsonPath("$[0].joinedAt").isString());
    }

    @Test
    void nonMemberCannotReadGroupOrMembers() throws Exception {
        User other = new User();
        other.setUsername("private_group_owner");
        other.setPassword("unused");
        other.setRoles(Set.of());
        other = userRepository.saveAndFlush(other);

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Private group", other)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, other)
        );

        mockMvc.perform(get("/api/groups/{id}", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/groups/{id}/members", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isForbidden());
    }

    @Test
    void leavingGroupRemovesMembershipAndOwnAccountAccess() throws Exception {
        User groupOwner = createUser("leave_group_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Leave group", groupOwner)
        );

        membershipRepository.save(new GroupMembership(group, groupOwner));
        membershipRepository.saveAndFlush(new GroupMembership(group, owner));

        Account account = accountRepository.save(
            new Account("My account", new BigDecimal("100"), "", owner)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, AccountPermission.EDIT)
        );

        mockMvc.perform(post("/api/groups/{id}/leave", group.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        membershipRepository.flush();

        assertFalse(
            membershipRepository.existsByGroup_IdAndUser(group.getId(), owner)
        );
        assertTrue(
            membershipRepository.existsByGroup_IdAndUser(
                group.getId(), groupOwner
            )
        );
        assertFalse(
            accessRepository.findAccess(
                group.getId(), account.getAccount_id()
            ).isPresent()
        );
        assertTrue(accountRepository.existsById(account.getAccount_id()));

        mockMvc.perform(get("/api/groups/{id}/accounts", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isForbidden());
    }

    @Test
    void groupOwnerCannotLeaveGroup() throws Exception {
        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Owner stays", owner)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, owner)
        );

        mockMvc.perform(post("/api/groups/{id}/leave", group.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isConflict());

        assertTrue(
            membershipRepository.existsByGroup_IdAndUser(group.getId(), owner)
        );
    }

    @Test
    void groupOwnerCanRemoveMemberAndTheirAccountAccess() throws Exception {
        User member = createUser("removed_group_member");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Managed group", owner)
        );

        membershipRepository.save(new GroupMembership(group, owner));
        membershipRepository.saveAndFlush(new GroupMembership(group, member));

        Account account = accountRepository.save(
            new Account("Member account", new BigDecimal("100"), "", member)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, AccountPermission.READ)
        );

        mockMvc.perform(delete(
                    "/api/groups/{groupId}/members/{userId}",
                    group.getId(), member.getId()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        membershipRepository.flush();

        assertFalse(
            membershipRepository.existsByGroup_IdAndUser(group.getId(), member)
        );
        assertFalse(
            accessRepository.findAccess(
                group.getId(), account.getAccount_id()
            ).isPresent()
        );
        assertTrue(accountRepository.existsById(account.getAccount_id()));
        assertTrue(userRepository.existsById(member.getId()));
    }

    @Test
    void ordinaryMemberCannotRemoveGroupMembers() throws Exception {
        User groupOwner = createUser("member_removal_group_owner");
        User target = createUser("member_removal_target");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Protected members", groupOwner)
        );

        membershipRepository.save(new GroupMembership(group, groupOwner));
        membershipRepository.save(new GroupMembership(group, owner));
        membershipRepository.saveAndFlush(new GroupMembership(group, target));

        mockMvc.perform(delete(
                    "/api/groups/{groupId}/members/{userId}",
                    group.getId(), target.getId()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(
            membershipRepository.existsByGroup_IdAndUser(group.getId(), target)
        );
    }

    @Test
    void ownerCanDeleteGroupWithoutDeletingAccountsOrUsers()
            throws Exception {

        User member = createUser("deleted_group_member");
        User recipient = createUser("deleted_group_invitation_recipient");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Group to delete", owner)
        );

        membershipRepository.save(new GroupMembership(group, owner));
        membershipRepository.save(new GroupMembership(group, member));

        Account account = accountRepository.save(
            new Account("Preserved account", new BigDecimal("100"), "", member)
        );

        accessRepository.save(
            new GroupAccountAccess(group, account, AccountPermission.READ)
        );

        GroupInvitation invitation = invitationRepository.saveAndFlush(
            new GroupInvitation(group, recipient, owner)
        );

        Long groupId = group.getId();
        Long accountId = account.getAccount_id();
        Long invitationId = invitation.getId();

        mockMvc.perform(delete("/api/groups/{id}", groupId)
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        groupRepository.flush();

        assertFalse(groupRepository.existsById(groupId));
        assertFalse(
            membershipRepository.existsByGroup_IdAndUser(groupId, owner)
        );
        assertFalse(
            membershipRepository.existsByGroup_IdAndUser(groupId, member)
        );
        assertFalse(invitationRepository.existsById(invitationId));
        assertTrue(accessRepository.findAccess(groupId, accountId).isEmpty());

        assertTrue(accountRepository.existsById(accountId));
        assertTrue(userRepository.existsById(owner.getId()));
        assertTrue(userRepository.existsById(member.getId()));
        assertTrue(userRepository.existsById(recipient.getId()));
    }

    @Test
    void ordinaryMemberCannotDeleteGroup() throws Exception {
        User groupOwner = createUser("protected_group_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Protected group", groupOwner)
        );

        membershipRepository.save(new GroupMembership(group, groupOwner));
        membershipRepository.saveAndFlush(new GroupMembership(group, owner));

        mockMvc.perform(delete("/api/groups/{id}", group.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(groupRepository.existsById(group.getId()));
        assertTrue(
            membershipRepository.existsByGroup_IdAndUser(group.getId(), owner)
        );
    }
}
