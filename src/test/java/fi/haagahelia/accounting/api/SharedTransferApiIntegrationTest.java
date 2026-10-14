package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class SharedTransferApiIntegrationTest
    extends AbstractGroupApiIntegrationTest {

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
}
