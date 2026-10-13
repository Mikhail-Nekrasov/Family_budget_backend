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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.GroupAccountAccess;
import fi.haagahelia.accounting.model.GroupInvitation;
import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.postgres.PostgresTestConfig;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.BudgetGroupRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;
import fi.haagahelia.accounting.repository.GroupInvitationRepository;
import fi.haagahelia.accounting.repository.GroupMembershipRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountAccessService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class GroupApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BudgetGroupRepository groupRepository;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    @Autowired
    private GroupInvitationRepository invitationRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private GroupAccountAccessRepository accessRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EntryRepository entryRepository;

    @Autowired
    private AccountAccessService accountAccessService;

    @Autowired
    private TransferRepository transferRepository;

    private User owner;

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

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused");
        user.setRoles(Set.of());

        return userRepository.saveAndFlush(user);
    }

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("group_api_owner");
        user.setPassword("unused");
        user.setRoles(Set.of());

        owner = userRepository.saveAndFlush(user);
    }

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

    @Test
    void memberCanShareOwnAccountAndChangePermission() throws Exception {
        User groupOwner = createUser("account_sharing_group_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Shared budget", groupOwner)
        );

        membershipRepository.save(new GroupMembership(group, groupOwner));
        membershipRepository.saveAndFlush(new GroupMembership(group, owner));

        Account account = accountRepository.saveAndFlush(
            new Account("Trip account", new BigDecimal("100"), "", owner)
        );

        mockMvc.perform(put(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(), account.getAccount_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"permission": "READ"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permission").value("READ"))
            .andExpect(jsonPath("$.name").value("Trip account"));

        mockMvc.perform(put(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(), account.getAccount_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"permission": "EDIT"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permission").value("EDIT"));

        accessRepository.flush();

        assertEquals(1, accessRepository.count());
        assertEquals(
            AccountPermission.EDIT,
            accessRepository.findAccess(
                group.getId(), account.getAccount_id()
            ).orElseThrow().getPermission()
        );
    }

    @Test
    void groupOwnerCannotShareAnotherUsersAccount() throws Exception {
        User other = createUser("private_account_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Owner group", owner)
        );
        membershipRepository.save(new GroupMembership(group, owner));
        membershipRepository.saveAndFlush(new GroupMembership(group, other));

        Account account = accountRepository.saveAndFlush(
            new Account("Private account", new BigDecimal("100"), "", other)
        );

        mockMvc.perform(put(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(), account.getAccount_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"permission": "EDIT"}
                    """))
            .andExpect(status().isForbidden());

        assertEquals(0, accessRepository.count());
    }

    @Test
    void memberSeesOnlyAccountsOpenedToTheGroup() throws Exception {
        User other = createUser("shared_accounts_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Shared accounts group", other)
        );

        membershipRepository.save(new GroupMembership(group, other));
        membershipRepository.saveAndFlush(new GroupMembership(group, owner));

        Account shared = accountRepository.save(
            new Account("Shared account", new BigDecimal("100"), "", other)
        );

        accountRepository.save(
            new Account("Private account", new BigDecimal("999"), "", other)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, shared, AccountPermission.READ)
        );

        mockMvc.perform(get("/api/groups/{id}/accounts", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Shared account"))
            .andExpect(jsonPath("$[0].amount").value(100))
            .andExpect(jsonPath("$[0].permission").value("READ"));
    }

    @Test
    void nonMemberCannotReadGroupAccounts() throws Exception {
        User other = createUser("unrelated_group_accounts_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Private group", other)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, other)
        );

        mockMvc.perform(get("/api/groups/{id}/accounts", group.getId())
                .with(user(owner.getUsername())))
            .andExpect(status().isForbidden());
    }

    @Test
    void accountOwnerCanRevokeAccessWithoutDeletingAccount() throws Exception {
        User other = createUser("revoked_access_group_member");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Revocation group", owner)
        );

        membershipRepository.save(new GroupMembership(group, owner));
        membershipRepository.saveAndFlush(new GroupMembership(group, other));

        Account account = accountRepository.save(
            new Account("Shared account", new BigDecimal("100"), "", owner)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, AccountPermission.READ)
        );

        mockMvc.perform(delete(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(), account.getAccount_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        accessRepository.flush();

        assertFalse(
            accessRepository.findAccess(
                group.getId(), account.getAccount_id()
            ).isPresent()
        );
        assertTrue(accountRepository.existsById(account.getAccount_id()));

        mockMvc.perform(get("/api/groups/{id}/accounts", group.getId())
                .with(user(other.getUsername())))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }

    @Test
    void groupOwnerCannotRevokeAnotherUsersAccountAccess() throws Exception {
        User other = createUser("revocation_account_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Owner group", owner)
        );

        membershipRepository.save(new GroupMembership(group, owner));
        membershipRepository.saveAndFlush(new GroupMembership(group, other));

        Account account = accountRepository.save(
            new Account("Other user's account", new BigDecimal("100"), "", other)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, AccountPermission.READ)
        );

        mockMvc.perform(delete(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(), account.getAccount_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(
            accessRepository.findAccess(
                group.getId(), account.getAccount_id()
            ).isPresent()
        );
    }

    @Test
    void memberCanReadSharedAccountEntriesUntilAccessIsRevoked()
            throws Exception {

        User accountOwner = createUser("shared_entries_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Entries group", accountOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, accountOwner)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, owner)
        );

        Account account = accountRepository.save(
            new Account("Shared history", BigDecimal.ZERO, "", accountOwner)
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, accountOwner)
        );

        entryRepository.saveAndFlush(
            new Entry(
                "Historical expense",
                EntryType.EXPENSE,
                new BigDecimal("10"),
                LocalDateTime.of(2026, 1, 1, 12, 0),
                "Created before sharing",
                category,
                account,
                accountOwner
            )
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, AccountPermission.READ)
        );

        mockMvc.perform(get(
                    "/api/groups/{groupId}/accounts/{accountId}/entries",
                    group.getId(), account.getAccount_id()
                )
                .with(user(owner.getUsername()))
                .param("type", "EXPENSE")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].title")
                .value("Historical expense"));

        mockMvc.perform(delete(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(), account.getAccount_id()
                )
                .with(user(accountOwner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        accessRepository.flush();

        mockMvc.perform(get(
                    "/api/groups/{groupId}/accounts/{accountId}/entries",
                    group.getId(), account.getAccount_id()
                )
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

    @Test
    void accountAccessDependsOnPermissionAndMembership() {
        User accountOwner = createUser("permission_account_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Permission group", accountOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, accountOwner)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, owner)
        );

        Account account = accountRepository.saveAndFlush(
            new Account("Permission account", BigDecimal.ZERO, "", accountOwner)
        );

        assertTrue(accountAccessService.canRead(account, accountOwner));
        assertTrue(accountAccessService.canEdit(account, accountOwner));

        assertFalse(accountAccessService.canRead(account, owner));
        assertFalse(accountAccessService.canEdit(account, owner));

        GroupAccountAccess access = accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, AccountPermission.READ)
        );

        assertTrue(accountAccessService.canRead(account, owner));
        assertFalse(accountAccessService.canEdit(account, owner));

        access.setPermission(AccountPermission.EDIT);
        accessRepository.saveAndFlush(access);

        assertTrue(accountAccessService.canRead(account, owner));
        assertTrue(accountAccessService.canEdit(account, owner));

        membershipRepository.deleteByGroup_IdAndUser(group.getId(), owner);
        membershipRepository.flush();

        // An access row alone is insufficient without group membership.
        assertFalse(accountAccessService.canRead(account, owner));
        assertFalse(accountAccessService.canEdit(account, owner));
    }

    @Test
    void readPermissionAllowsReceivingButNotWithdrawing() throws Exception {
        User other = createUser("transfer_shared_account_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Transfer group", other)
        );

        membershipRepository.save(new GroupMembership(group, other));
        membershipRepository.saveAndFlush(new GroupMembership(group, owner));

        Account ownAccount = accountRepository.save(
            new Account("My account", new BigDecimal("100"), "", owner)
        );

        Account sharedAccount = accountRepository.save(
            new Account("Shared account", new BigDecimal("50"), "", other)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(
                group, sharedAccount, AccountPermission.READ
            )
        );

        long transfersBefore = transferRepository.count();

        // READ permits receiving money into the shared account.
        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "fromAccountId": %d,
                    "toAccountId": %d,
                    "amount": 25,
                    "description": "Allowed deposit"
                    }
                    """.formatted(
                        ownAccount.getAccount_id(),
                        sharedAccount.getAccount_id()
                    )))
            .andExpect(status().isCreated());

        accountRepository.flush();

        assertEquals(
            0,
            new BigDecimal("75").compareTo(
                accountRepository.findById(ownAccount.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );
        assertEquals(
            0,
            new BigDecimal("75").compareTo(
                accountRepository.findById(sharedAccount.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );

        // READ does not permit withdrawing from the shared account.
        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "fromAccountId": %d,
                    "toAccountId": %d,
                    "amount": 10,
                    "description": "Forbidden withdrawal"
                    }
                    """.formatted(
                        sharedAccount.getAccount_id(),
                        ownAccount.getAccount_id()
                    )))
            .andExpect(status().isForbidden());

        assertEquals(transfersBefore + 1, transferRepository.count());

        assertEquals(
            0,
            new BigDecimal("75").compareTo(
                accountRepository.findById(ownAccount.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );
        assertEquals(
            0,
            new BigDecimal("75").compareTo(
                accountRepository.findById(sharedAccount.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );
    }

    @ParameterizedTest
    @CsvSource({
        "READ, 403, 90, true",
        "EDIT, 204, 100, false"
    })
    void deletingSharedEntryRequiresEditPermission(
            AccountPermission permission,
            int expectedStatus,
            int expectedBalance,
            boolean expectedEntryExists) throws Exception {

        User accountOwner = createUser("shared_entry_delete_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Entry deletion group", accountOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, accountOwner)
        );
        membershipRepository.saveAndFlush(
            new GroupMembership(group, owner)
        );

        // The balance already includes the expense of 10.
        Account account = accountRepository.save(
            new Account(
                "Shared expense account",
                new BigDecimal("90"),
                "",
                accountOwner
            )
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, accountOwner)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Shared expense",
                EntryType.EXPENSE,
                new BigDecimal("10"),
                LocalDateTime.of(2026, 10, 13, 12, 0),
                "",
                category,
                account,
                accountOwner
            )
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, permission)
        );

        mockMvc.perform(delete("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().is(expectedStatus));

        entryRepository.flush();

        assertEquals(
            expectedEntryExists,
            entryRepository.existsById(entry.getId())
        );

        Account reloaded = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            BigDecimal.valueOf(expectedBalance).compareTo(reloaded.getAmount())
        );
    }
}