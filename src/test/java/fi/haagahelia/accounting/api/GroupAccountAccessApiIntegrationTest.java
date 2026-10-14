package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.GroupAccountAccess;
import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;

class GroupAccountAccessApiIntegrationTest
    extends AbstractGroupApiIntegrationTest {

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
}
