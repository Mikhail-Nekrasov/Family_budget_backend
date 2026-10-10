package fi.haagahelia.accounting.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.TransferService;
import fi.haagahelia.accounting.service.TransferService.TransferResult;

public class TransferControllerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Model model;

    @Mock
    private Authentication auth;

    @Mock
    private TransferService transferService;

    @InjectMocks
    private TransferController transferController;

    private User user;
    private Account account1;
    private Account account2;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        
        user = new User();
        user.setId(1L);
        user.setUsername("testuser");

        account1 = new Account("Account 1", new BigDecimal("1000"), "Description 1", user);
        account2 = new Account("Account 2", new BigDecimal("500"), "Description 2", user);

        when(auth.getName()).thenReturn(user.getUsername());
        when(userRepository.findByUsername(user.getUsername())).thenReturn(user);
    }

    @Test
    public void testShowTransferFormReturnsView() {
        when(accountRepository.findById(2L)).thenReturn(Optional.of(account2));
        when(accountRepository.findByUser(user)).thenReturn(Arrays.asList(account1, account2));

        String view = transferController.showTransferForm(2L, model, auth, null);

        assertEquals("transfer", view);
        verify(model).addAttribute("toAccount", account2);
        verify(model).addAttribute(eq("accounts"), anyList());
    }

    @Test
    public void testExecuteTransferSuccess() {
        BigDecimal amount = new BigDecimal("200");

        when(transferService.executeTransfer(
            1L, 2L, amount, "Payment", user
        )).thenReturn(TransferResult.SUCCESS);

        String result = transferController.executeTransfer(
            1L, 2L, amount, "Payment", auth
        );

        assertEquals("redirect:/transfers", result);

        verify(transferService).executeTransfer(
            1L, 2L, amount, "Payment", user
        );
        verifyNoInteractions(accountRepository, transferRepository);
    }

    @Test
    public void testExecuteTransferNotEnoughMoney() {
        BigDecimal amount = new BigDecimal("2000");

        when(transferService.executeTransfer(
            1L, 2L, amount, "Payment", user
        )).thenReturn(TransferResult.INSUFFICIENT_FUNDS);

        String result = transferController.executeTransfer(
            1L, 2L, amount, "Payment", auth
        );

        assertEquals("redirect:/transfer/2?error=nomoney", result);
    }

    @Test
    public void testExecuteTransferInvalidAmount() {
        BigDecimal amount = new BigDecimal("-50");

        when(transferService.executeTransfer(
            1L, 2L, amount, "Payment", user
        )).thenReturn(TransferResult.INVALID_AMOUNT);

        String result = transferController.executeTransfer(
            1L, 2L, amount, "Payment", auth
        );

        assertEquals("redirect:/transfer/2?error=invalid", result);
    }

    @Test
    public void testExecuteTransferSameAccounts() {
        BigDecimal amount = new BigDecimal("100");

        when(transferService.executeTransfer(
            1L, 1L, amount, "Payment", user
        )).thenReturn(TransferResult.SAME_ACCOUNT);

        String result = transferController.executeTransfer(
            1L, 1L, amount, "Payment", auth
        );

        assertEquals("redirect:/transfer/1?error=same", result);
    }

    @Test
    public void testExecuteTransferInvalidAccounts() {
        BigDecimal amount = new BigDecimal("100");

        when(transferService.executeTransfer(
            1L, 2L, amount, "Payment", user
        )).thenReturn(TransferResult.INVALID_ACCOUNTS);

        String result = transferController.executeTransfer(
            1L, 2L, amount, "Payment", auth
        );

        assertEquals("redirect:/accounts", result);
        verifyNoInteractions(accountRepository, transferRepository);
    }

    @Test
    public void testListTransfersReturnsCorrectView() {
        Transfer t1 = new Transfer(account1, account2, new BigDecimal("100"), "Desc 1");
        Transfer t2 = new Transfer(account2, account1, new BigDecimal("50"), "Desc 2");

    when(transferRepository.findVisibleToUser(user))
        .thenReturn(Arrays.asList(t1, t2));

        String view = transferController.listTransfers(model, auth);

        assertEquals("transfers", view);
        verify(model).addAttribute(eq("transfers"), anyList());
        verify(transferRepository).findVisibleToUser(user);
        verify(transferRepository, never()).findAll();
    }
}
