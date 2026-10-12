package fi.haagahelia.accounting.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;

public class AccountControllerTest {

    private AccountController accountController;
    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private Authentication auth;
    private Model model;

    @BeforeEach
    public void setUp() {
        accountRepository = Mockito.mock(AccountRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        auth = Mockito.mock(Authentication.class);
        model = Mockito.mock(Model.class);

        AccountService accountService = new AccountService(accountRepository);

        accountController = new AccountController(
            accountRepository,
            userRepository,
            accountService
        );

        User testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("test_user");
        when(auth.getName()).thenReturn("test_user");
        when(userRepository.findByUsername("test_user")).thenReturn(testUser);
    }

    @Test
    public void testAccountsReturnsCorrectView() {
        Account acc1 = new Account("Acc1", BigDecimal.valueOf(1000), "Desc1", userRepository.findByUsername("test_user"));
        Account acc2 = new Account("Acc2", BigDecimal.valueOf(500), "Desc2", userRepository.findByUsername("test_user"));
        acc1.setEntries(Arrays.asList(new Entry("Expense1", EntryType.EXPENSE, BigDecimal.valueOf(200), null, "", null, null, acc1.getUser())));
        acc2.setEntries(null);

        List<Account> accountList = Arrays.asList(acc1, acc2);
        when(accountRepository.findByUser(any(User.class))).thenReturn(accountList);

        String viewName = accountController.accounts(null, model, auth);

        assertEquals("accounts", viewName);
    }

    @Test
    public void testShowAddAccountForm() {
        String viewName = accountController.showAddAccountForm(model, auth);
        assertEquals("addaccount", viewName);
    }

    @Test
    public void testSaveAccount() {
        Account account = new Account("AccTest", null, "DescTest", null);
        String viewName = accountController.saveAccount(account, auth);

        assertEquals("redirect:/accounts", viewName);
        assertNotNull(account.getUser()); 
        assertEquals(BigDecimal.ZERO, account.getAmount()); 
        verify(accountRepository, times(1)).save(account);
    }

    @Test
    public void testDeleteAccountWithoutEntriesOrTransfers() {
        Account account = new Account("AccDel", BigDecimal.valueOf(100), "DescDel", userRepository.findByUsername("test_user"));
        account.setEntries(null); 
        account.setFromTransfers(null);
        account.setToTransfers(null);

        when(accountRepository.findById(1L)).thenReturn(java.util.Optional.of(account));

        String viewName = accountController.deleteAccount(1L, auth);

        assertEquals("redirect:/accounts", viewName);
        verify(accountRepository, times(1)).deleteById(1L);
    }

    @Test
    public void testDeleteAccountWithEntries() {
        Account account = new Account("AccDel", BigDecimal.valueOf(100), "DescDel", userRepository.findByUsername("test_user"));
        account.setEntries(Arrays.asList(new Entry()));
        account.setFromTransfers(null);
        account.setToTransfers(null);

        when(accountRepository.findById(2L)).thenReturn(java.util.Optional.of(account));

        String viewName = accountController.deleteAccount(2L, auth);

        assertEquals("redirect:/accounts", viewName);
        verify(accountRepository, never()).deleteById(2L);
    }

    @Test
    public void allowsDeletingAccountWithSameOwnerId() {
        User loadedOwner = new User();
        loadedOwner.setId(1L);
        loadedOwner.setUsername("test_user");

        Account account = new Account(
            "Own account", BigDecimal.ZERO, "", loadedOwner
        );

        when(accountRepository.findById(10L))
            .thenReturn(java.util.Optional.of(account));

        String result = accountController.deleteAccount(10L, auth);

        assertEquals("redirect:/accounts", result);
        verify(accountRepository).deleteById(10L);
    }

    @Test
    public void rejectsDeletingForeignAccount() {
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("other_user");

        Account account = new Account(
            "Foreign account", BigDecimal.ZERO, "", otherUser
        );

        when(accountRepository.findById(10L))
            .thenReturn(java.util.Optional.of(account));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> accountController.deleteAccount(10L, auth)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(accountRepository, never()).deleteById(anyLong());
    }

    @Test
    public void rejectsMissingOrBlankAccountName() {
        Account account = new Account();
        account.setAmount(new BigDecimal("100"));

        for (String name : new String[] {
                null, "", "   ", "\t", "\n", " \t\n "
        }) {
            account.setName(name);

            ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> accountController.saveAccount(account, auth)
            );

            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        }

        assertEquals(new BigDecimal("100"), account.getAmount());
        assertNull(account.getUser());

        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    public void preventsDeletingAccountWithTransfer() {
        User owner = userRepository.findByUsername("test_user");

        Account account = new Account(
            "Source", new BigDecimal("100"), "", owner
        );
        Account destination = new Account(
            "Destination", BigDecimal.ZERO, "", owner
        );

        Transfer transfer = new Transfer(
            account, destination, new BigDecimal("50"), "Payment"
        );

        account.setFromTransfers(List.of(transfer));

        when(accountRepository.findById(10L))
            .thenReturn(java.util.Optional.of(account));

        String result = accountController.deleteAccount(10L, auth);

        assertEquals("redirect:/accounts", result);
        verify(accountRepository, never()).deleteById(anyLong());
    }
}
