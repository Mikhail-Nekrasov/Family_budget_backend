package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.GroupAccountAccess;
import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;

class SharedEntryApiIntegrationTest
    extends AbstractGroupApiIntegrationTest {

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

    @ParameterizedTest
    @CsvSource({
        "READ, 403, 100, 0",
        "EDIT, 201, 90, 1"
    })
    void creatingSharedEntryRequiresEditPermission(
            AccountPermission permission,
            int expectedStatus,
            int expectedBalance,
            int expectedCreatedEntries) throws Exception {

        User accountOwner = createUser("shared_entry_create_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Entry creation group", accountOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, accountOwner)
        );
        membershipRepository.save(
            new GroupMembership(group, owner)
        );

        Account account = accountRepository.save(
            new Account(
                "Shared account",
                new BigDecimal("100"),
                "",
                accountOwner
            )
        );

        // The acting user can use their own category.
        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, permission)
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(post("/api/entries")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Shared expense",
                        "type": "EXPENSE",
                        "amount": 10,
                        "dateTime": "2026-10-14T12:00:00",
                        "description": "",
                        "accountId": %d,
                        "categoryId": %d
                    }
                    """.formatted(
                        account.getAccount_id(),
                        category.getCategory_id()
                    )))
            .andExpect(status().is(expectedStatus));

        entryRepository.flush();

        assertEquals(
            entriesBefore + expectedCreatedEntries,
            entryRepository.count()
        );

        Account reloaded = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            BigDecimal.valueOf(expectedBalance)
                .compareTo(reloaded.getAmount())
        );

        if (permission == AccountPermission.EDIT) {
            // The expense belongs to the account owner's financial records.
            mockMvc.perform(get("/api/entries")
                    .with(user(accountOwner.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title")
                    .value("Shared expense"));

            // It does not enter the acting user's personal records.
            mockMvc.perform(get("/api/entries")
                    .with(user(owner.getUsername())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

            // Revoking access also removes access through the group.
            accessRepository.delete(
                accessRepository.findAccess(
                    group.getId(),
                    account.getAccount_id()
                ).orElseThrow()
            );
            accessRepository.flush();

            mockMvc.perform(get(
                    "/api/groups/{groupId}/accounts/{accountId}/entries",
                    group.getId(),
                    account.getAccount_id()
                ).with(user(owner.getUsername())))
                .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "READ, 403, 90, 10",
        "EDIT, 200, 75, 25"
    })
    void editingSharedEntryRequiresEditPermission(
            AccountPermission permission,
            int expectedStatus,
            int expectedBalance,
            int expectedAmount) throws Exception {

        User accountOwner = createUser("shared_entry_edit_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Entry editing group", accountOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, accountOwner)
        );
        membershipRepository.save(
            new GroupMembership(group, owner)
        );

        // The balance already includes the original expense of 10.
        Account account = accountRepository.save(
            new Account(
                "Shared account",
                new BigDecimal("90"),
                "",
                accountOwner
            )
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Original expense",
                EntryType.EXPENSE,
                new BigDecimal("10"),
                LocalDateTime.of(2026, 10, 14, 12, 0),
                "",
                category,
                account,
                accountOwner
            )
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, account, permission)
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(put("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Updated expense",
                        "type": "EXPENSE",
                        "amount": 25,
                        "dateTime": "2026-10-14T13:00:00",
                        "description": "Updated description",
                        "accountId": %d,
                        "categoryId": %d
                    }
                    """.formatted(
                        account.getAccount_id(),
                        category.getCategory_id()
                    )))
            .andExpect(status().is(expectedStatus));

        entryRepository.flush();

        assertEquals(entriesBefore, entryRepository.count());

        Entry reloadedEntry = entryRepository
            .findById(entry.getId())
            .orElseThrow();

        assertEquals(
            0,
            BigDecimal.valueOf(expectedAmount)
                .compareTo(reloadedEntry.getAmount())
        );

        assertEquals(
            permission == AccountPermission.EDIT
                ? "Updated expense"
                : "Original expense",
            reloadedEntry.getTitle()
        );

        assertEquals(
            accountOwner.getId(),
            reloadedEntry.getUser().getId()
        );

        Account reloadedAccount = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            BigDecimal.valueOf(expectedBalance)
                .compareTo(reloadedAccount.getAmount())
        );
    }

    @Test
    void accountOwnerCanEditEntryKeepingAnotherUsersCategory()
            throws Exception {

        User accountOwner = createUser("existing_category_account_owner");

        Account account = accountRepository.save(
            new Account(
                "Account",
                new BigDecimal("90"),
                "",
                accountOwner
            )
        );

        // Another participant's category is already used by this entry.
        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        Entry entry = entryRepository.saveAndFlush(
            new Entry(
                "Original expense",
                EntryType.EXPENSE,
                new BigDecimal("10"),
                LocalDateTime.of(2026, 10, 14, 12, 0),
                "",
                category,
                account,
                accountOwner
            )
        );

        mockMvc.perform(put("/api/entries/{id}", entry.getId())
                .with(user(accountOwner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Updated expense",
                        "type": "EXPENSE",
                        "amount": 25,
                        "dateTime": "2026-10-14T13:00:00",
                        "description": "",
                        "accountId": %d,
                        "categoryId": %d
                    }
                    """.formatted(
                        account.getAccount_id(),
                        category.getCategory_id()
                    )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Updated expense"))
            .andExpect(jsonPath("$.categoryId")
                .value(category.getCategory_id().intValue()));

        entryRepository.flush();

        Entry updated = entryRepository.findById(entry.getId())
            .orElseThrow();

        assertEquals(
            category.getCategory_id(),
            updated.getCategory().getCategory_id()
        );

        assertEquals(
            0,
            new BigDecimal("25").compareTo(updated.getAmount())
        );

        assertEquals(
            0,
            new BigDecimal("75").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );
    }

    @ParameterizedTest
    @CsvSource({
        "EDIT, EDIT, 200, 100, 175",
        "READ, EDIT, 403, 90, 200",
        "EDIT, READ, 403, 90, 200"
    })
    void movingSharedEntryRequiresEditPermissionOnBothAccounts(
            AccountPermission sourcePermission,
            AccountPermission targetPermission,
            int expectedStatus,
            int expectedSourceBalance,
            int expectedTargetBalance) throws Exception {

        User sourceOwner = createUser("entry_move_source_owner");
        User targetOwner = createUser("entry_move_target_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Entry move group", sourceOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, sourceOwner)
        );
        membershipRepository.save(
            new GroupMembership(group, targetOwner)
        );
        membershipRepository.save(
            new GroupMembership(group, owner)
        );

        // The source balance already includes the expense of 10.
        Account source = accountRepository.save(
            new Account(
                "Source account",
                new BigDecimal("90"),
                "",
                sourceOwner
            )
        );

        Account target = accountRepository.save(
            new Account(
                "Target account",
                new BigDecimal("200"),
                "",
                targetOwner
            )
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Original expense",
                EntryType.EXPENSE,
                new BigDecimal("10"),
                LocalDateTime.of(2026, 10, 14, 12, 0),
                "",
                category,
                source,
                sourceOwner
            )
        );

        accessRepository.save(
            new GroupAccountAccess(group, source, sourcePermission)
        );
        accessRepository.saveAndFlush(
            new GroupAccountAccess(group, target, targetPermission)
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(put("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Moved expense",
                        "type": "EXPENSE",
                        "amount": 25,
                        "dateTime": "2026-10-14T13:00:00",
                        "description": "",
                        "accountId": %d,
                        "categoryId": %d
                    }
                    """.formatted(
                        target.getAccount_id(),
                        category.getCategory_id()
                    )))
            .andExpect(status().is(expectedStatus));

        entryRepository.flush();

        assertEquals(entriesBefore, entryRepository.count());

        Entry updated = entryRepository.findById(entry.getId())
            .orElseThrow();

        boolean moved = expectedStatus == 200;

        assertEquals(
            moved ? target.getAccount_id() : source.getAccount_id(),
            updated.getAccount().getAccount_id()
        );

        assertEquals(
            moved ? targetOwner.getId() : sourceOwner.getId(),
            updated.getUser().getId()
        );

        assertEquals(
            moved ? "Moved expense" : "Original expense",
            updated.getTitle()
        );

        assertEquals(
            0,
            new BigDecimal(moved ? "25" : "10")
                .compareTo(updated.getAmount())
        );

        assertEquals(
            0,
            BigDecimal.valueOf(expectedSourceBalance).compareTo(
                accountRepository.findById(source.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );

        assertEquals(
            0,
            BigDecimal.valueOf(expectedTargetBalance).compareTo(
                accountRepository.findById(target.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );
    }

    @Test
    void revokedAccessPreventsCreatingEditingAndDeletingEntries()
            throws Exception {

        User accountOwner = createUser("revoked_entry_access_owner");

        BudgetGroup group = groupRepository.save(
            new BudgetGroup("Revoked entry access", accountOwner)
        );

        membershipRepository.save(
            new GroupMembership(group, accountOwner)
        );
        membershipRepository.save(
            new GroupMembership(group, owner)
        );

        // The balance already includes the expense of 10.
        Account account = accountRepository.save(
            new Account(
                "Shared account",
                new BigDecimal("90"),
                "",
                accountOwner
            )
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Original expense",
                EntryType.EXPENSE,
                new BigDecimal("10"),
                LocalDateTime.of(2026, 10, 14, 12, 0),
                "",
                category,
                account,
                accountOwner
            )
        );

        accessRepository.saveAndFlush(
            new GroupAccountAccess(
                group,
                account,
                AccountPermission.EDIT
            )
        );

        assertEquals(true, accountAccessService.canEdit(account, owner));

        // Revoke access through the API as the account owner.
        mockMvc.perform(delete(
                    "/api/groups/{groupId}/accounts/{accountId}",
                    group.getId(),
                    account.getAccount_id()
                )
                .with(user(accountOwner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        accessRepository.flush();

        assertEquals(false, accountAccessService.canEdit(account, owner));

        long entriesBefore = entryRepository.count();

        String requestBody = """
            {
                "title": "Forbidden expense",
                "type": "EXPENSE",
                "amount": 25,
                "dateTime": "2026-10-14T13:00:00",
                "description": "",
                "accountId": %d,
                "categoryId": %d
            }
            """.formatted(
                account.getAccount_id(),
                category.getCategory_id()
            );

        mockMvc.perform(post("/api/entries")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        entryRepository.flush();

        assertEquals(entriesBefore, entryRepository.count());

        Entry unchanged = entryRepository.findById(entry.getId())
            .orElseThrow();

        assertEquals("Original expense", unchanged.getTitle());
        assertEquals(accountOwner.getId(), unchanged.getUser().getId());
        assertEquals(
            account.getAccount_id(),
            unchanged.getAccount().getAccount_id()
        );
        assertEquals(
            0,
            new BigDecimal("10").compareTo(unchanged.getAmount())
        );

        assertEquals(
            0,
            new BigDecimal("90").compareTo(
                accountRepository.findById(account.getAccount_id())
                    .orElseThrow().getAmount()
            )
        );
    }
}
