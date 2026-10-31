package fi.haagahelia.accounting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.service.TransferService.TransferResult;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private GroupAccountAccessRepository accessRepository;

    private TransferService transferService;

    private User user;
    private Account from;
    private Account to;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        transferService = new TransferService(
            accountRepository,
            transferRepository,
            new AccountAccessService(accessRepository)
        );

        from = new Account(
            "From", new BigDecimal("1000"), "Source account", user
        );
        to = new Account(
            "To", new BigDecimal("500"), "Destination account", user
        );

        ReflectionTestUtils.setField(from, "account_id", 10L);
        ReflectionTestUtils.setField(to, "account_id", 20L);
    }

    private void mockAccounts() {
        when(accountRepository.findById(10L))
            .thenReturn(Optional.of(from));
        when(accountRepository.findById(20L))
            .thenReturn(Optional.of(to));
    }

    private void assertNoChanges() {
        assertEquals(new BigDecimal("1000"), from.getAmount());
        assertEquals(new BigDecimal("500"), to.getAmount());

        verify(accountRepository, never()).save(any(Account.class));
        verify(transferRepository, never()).save(any(Transfer.class));
    }

    @Test
    void transfersMoneyAndSavesTransfer() {
        mockAccounts();

        TransferResult result = transferService.executeTransfer(
            10L, 20L, new BigDecimal("200"), "Payment", user
        );

        assertEquals(TransferResult.SUCCESS, result);
        assertEquals(new BigDecimal("800"), from.getAmount());
        assertEquals(new BigDecimal("700"), to.getAmount());

        verify(accountRepository).save(from);
        verify(accountRepository).save(to);

        ArgumentCaptor<Transfer> captor =
            ArgumentCaptor.forClass(Transfer.class);

        verify(transferRepository).save(captor.capture());

        Transfer saved = captor.getValue();
        assertEquals(from, saved.getFromAccount());
        assertEquals(to, saved.getToAccount());
        assertEquals(new BigDecimal("200"), saved.getAmount());
        assertEquals("Payment", saved.getDescription());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-50", "25.001"})
    void rejectsInvalidAmount(String amountText) {
        mockAccounts();

        BigDecimal amount =
            amountText == null ? null : new BigDecimal(amountText);

        TransferResult result = transferService.executeTransfer(
            10L, 20L, amount, "Payment", user
        );

        assertEquals(TransferResult.INVALID_AMOUNT, result);
        assertNoChanges();
    }

    @Test
    void rejectsInsufficientFunds() {
        mockAccounts();

        TransferResult result = transferService.executeTransfer(
            10L, 20L, new BigDecimal("1001"), "Payment", user
        );

        assertEquals(TransferResult.INSUFFICIENT_FUNDS, result);
        assertNoChanges();
    }

    @Test
    void rejectsSameAccount() {
        when(accountRepository.findById(10L))
            .thenReturn(Optional.of(from));

        TransferResult result = transferService.executeTransfer(
            10L, 10L, new BigDecimal("100"), "Payment", user
        );

        assertEquals(TransferResult.SAME_ACCOUNT, result);
        assertNoChanges();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsForeignAccount(boolean foreignSource) {
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("otheruser");

        if (foreignSource) {
            from.setUser(otherUser);
        } else {
            to.setUser(otherUser);
        }

        mockAccounts();

        TransferResult result = transferService.executeTransfer(
            10L, 20L, new BigDecimal("100"), "Payment", user
        );

        assertEquals(TransferResult.INVALID_ACCOUNTS, result);
        assertNoChanges();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsMissingAccount(boolean missingSource) {
        when(accountRepository.findById(10L))
            .thenReturn(missingSource ? Optional.empty() : Optional.of(from));
        when(accountRepository.findById(20L))
            .thenReturn(missingSource ? Optional.of(to) : Optional.empty());

        TransferResult result = transferService.executeTransfer(
            10L, 20L, new BigDecimal("100"), "Payment", user
        );

        assertEquals(TransferResult.INVALID_ACCOUNTS, result);
        assertNoChanges();
    }

    @Test
    void allowsAccountsOwnedByAnotherObjectWithSameUserId() {
        User loadedOwner = new User();
        loadedOwner.setId(user.getId());
        loadedOwner.setUsername(user.getUsername());

        from.setUser(loadedOwner);
        to.setUser(loadedOwner);

        mockAccounts();

        TransferResult result = transferService.executeTransfer(
            10L, 20L, new BigDecimal("200"), "Payment", user
        );

        assertEquals(TransferResult.SUCCESS, result);
        assertEquals(new BigDecimal("800"), from.getAmount());
        assertEquals(new BigDecimal("700"), to.getAmount());

        verify(accountRepository).save(from);
        verify(accountRepository).save(to);
        verify(transferRepository).save(any(Transfer.class));
    }

    @Test
    void convertsTransferUsingExplicitDestinationAmount() {
        mockAccounts();

        from.setCurrencyCode("EUR");
        to.setCurrencyCode("USD");

        TransferResult result = transferService.executeTransferWithAmounts(
            10L,
            20L,
            new BigDecimal("100.00"),
            new BigDecimal("108.50"),
            "Conversion",
            user,
            null
        );

        assertEquals(TransferResult.SUCCESS, result);
        assertEquals(
            0,
            new BigDecimal("900.00").compareTo(from.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("608.50").compareTo(to.getAmount())
        );

        ArgumentCaptor<Transfer> captor =
            ArgumentCaptor.forClass(Transfer.class);

        verify(transferRepository).save(captor.capture());

        Transfer saved = captor.getValue();

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(saved.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("108.50").compareTo(saved.getTargetAmount())
        );
        assertEquals("EUR", saved.getSourceCurrencyCode());
        assertEquals("USD", saved.getTargetCurrencyCode());
    }

    @Test
    void rejectsDifferentAmountsForSameCurrency() {
        mockAccounts();

        from.setCurrencyCode("EUR");
        to.setCurrencyCode("EUR");

        TransferResult result = transferService.executeTransferWithAmounts(
            10L,
            20L,
            new BigDecimal("100.00"),
            new BigDecimal("90.00"),
            "Invalid transfer",
            user,
            null
        );

        assertEquals(TransferResult.INVALID_TARGET_AMOUNT, result);
        assertNoChanges();
        verifyNoInteractions(transferRepository);
    }
}