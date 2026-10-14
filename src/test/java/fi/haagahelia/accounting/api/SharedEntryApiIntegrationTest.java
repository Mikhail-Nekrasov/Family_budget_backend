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
}
