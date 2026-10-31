package fi.haagahelia.accounting.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;

import jakarta.servlet.http.HttpSession;
import fi.haagahelia.accounting.api.dto.EntrySummaryResponse;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;
import fi.haagahelia.accounting.repository.SyncTombstoneRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountAccessService;
import fi.haagahelia.accounting.service.EntryService;
import fi.haagahelia.accounting.service.EntrySummaryService;

public class EntryControllerTest {

    @Mock
    private EntryRepository entryRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Model model;

    @Mock
    private Authentication auth;

    @Mock
    private HttpSession session;

    @Mock
    private EntrySummaryService entrySummaryService;

    private EntryController entryController;

    private User testUser;
    private Category testCategory;
    private Account testAccount;
    private Entry testEntry;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        EntryService entryService = new EntryService(
            entryRepository,
            accountRepository,
            categoryRepository,
            new AccountAccessService(
                mock(GroupAccountAccessRepository.class)
            ),
            mock(SyncTombstoneRepository.class)
        );

        entryController = new EntryController(
            entryRepository,
            categoryRepository,
            accountRepository,
            userRepository,
            entryService,
            entrySummaryService
        );

        // Set up test user
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("test_user");

        // Set up test category
        testCategory = new Category();
        ReflectionTestUtils.setField(testCategory, "category_id", 20L);
        testCategory.setName("TestCategory");
        testCategory.setType(EntryType.INCOME);
        testCategory.setUser(testUser);

        // Set up test account
        testAccount = new Account();
        ReflectionTestUtils.setField(testAccount, "account_id", 10L);
        testAccount.setName("TestAccount");
        testAccount.setAmount(BigDecimal.valueOf(1000));
        testAccount.setUser(testUser);     

        // Set up test entry
        testEntry = new Entry(
            "TestEntry",
            EntryType.INCOME,
            BigDecimal.valueOf(500),
            LocalDateTime.now(),
            "Description",
            testCategory,
            testAccount,
            testUser
        );

        // Configure repository mocks
        when(accountRepository.findById(10L))
            .thenReturn(Optional.of(testAccount));

        when(categoryRepository.findById(20L))
            .thenReturn(Optional.of(testCategory));

        // Mock authentication
        when(auth.getName()).thenReturn("test_user");
        when(userRepository.findByUsername("test_user")).thenReturn(testUser);

        // Mock entry repository for findById
        when(entryRepository.findById(anyLong())).thenReturn(Optional.of(testEntry));

        // Mock account repository save
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArguments()[0]);
    }

    @Test
    public void testGetEntriesReturnsCorrectView() {
        Page<Entry> page = new PageImpl<>(List.of(testEntry));

        when(entryRepository.findByUser(
            eq(testUser),
            any(Pageable.class)
        )).thenReturn(page);

        when(entrySummaryService.getSummary(
            testUser,
            "EUR"
        )).thenReturn(new EntrySummaryResponse(
            new BigDecimal("500.00"),
            new BigDecimal("150.00"),
            new BigDecimal("350.00"),
            "EUR"
        ));

        String view = entryController.getEntries(
            null,
            0,
            5,
            model,
            auth,
            "EUR"
        );

        assertEquals("entries", view);

        verify(model).addAttribute(eq("entries"), anyList());
        verify(model).addAttribute("pageTitle", "All entries");
        verify(model).addAttribute(
            "total",
            new BigDecimal("350.00")
        );
        verify(model).addAttribute("currencyCode", "EUR");

        verify(entryRepository, never()).findByUser(testUser);
    }

    @Test
    public void testAddEntryReturnsCorrectView() {
        String view = entryController.addEntry(EntryType.INCOME, model, session, auth);
        assertEquals("addentry", view);
    }

    @Test
    public void testEditEntryReturnsCorrectView() {
        String view = entryController.editEntry(1L, model, auth, session);
        assertEquals("editentry", view);
    }

    @Test
    public void testSaveNewEntry() {
        String redirect = entryController.saveNewEntry(testEntry, EntryType.INCOME, auth, session);
        assertEquals("redirect:/entries?type=INCOME", redirect);
        verify(entryRepository).save(testEntry);
        verify(accountRepository).save(testAccount);
    }

    @Test
    public void testSaveEditedEntry() {
        // Change entry amount
        Entry editedEntry = new Entry();
        editedEntry.setTitle("EditedEntry");
        editedEntry.setType(EntryType.INCOME);
        editedEntry.setAmount(BigDecimal.valueOf(800));
        editedEntry.setCategory(testCategory);
        editedEntry.setAccount(testAccount);
        editedEntry.setUser(testUser);
        editedEntry.setDateTime(LocalDateTime.now());

        String redirect = entryController.saveEditedEntry(1L, editedEntry, auth, session);
        assertEquals("redirect:/entries?type=INCOME", redirect);
        assertEquals(BigDecimal.valueOf(1300), testAccount.getAmount()); // 1000 + 500(original) + 800(new)
    }

    @Test
    public void testDeleteEntry() {
        String redirect = entryController.deleteEntry(1L, EntryType.INCOME, auth);
        assertEquals("redirect:/entries?type=INCOME", redirect);
        verify(entryRepository).delete(testEntry);
        verify(accountRepository).save(testAccount);
    }


    @Test
    public void rejectsForeignAccountOnCreateAndEdit() {
        User other = new User();
        other.setId(2L);
        testAccount.setUser(other);
        assertForbiddenWithoutWrites(false);
        assertForbiddenWithoutWrites(true);
    }

    @Test
    public void rejectsForeignCategoryOnCreateAndEdit() {
        // The saved entry originally uses the current user's category.
        Entry existingEntry = new Entry(
            "Existing entry",
            EntryType.INCOME,
            new BigDecimal("500"),
            LocalDateTime.now(),
            "",
            testCategory,
            testAccount,
            testUser
        );

        when(entryRepository.findById(1L))
            .thenReturn(Optional.of(existingEntry));

        // The request selects a different category owned by another user.
        User other = new User();
        other.setId(2L);

        Category foreignCategory = new Category();
        foreignCategory.setName("Foreign category");
        foreignCategory.setType(EntryType.INCOME);
        foreignCategory.setUser(other);

        ReflectionTestUtils.setField(
            foreignCategory,
            "category_id",
            30L
        );

        when(categoryRepository.findById(30L))
            .thenReturn(Optional.of(foreignCategory));

        testEntry.setCategory(foreignCategory);

        assertForbiddenWithoutWrites(false);
        assertForbiddenWithoutWrites(true);
    }

    private void assertForbiddenWithoutWrites(boolean edit) {
        BigDecimal before = testAccount.getAmount();
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            if (edit) {
                entryController.saveEditedEntry(1L, testEntry, auth, session);
            } else {
                entryController.saveNewEntry(testEntry, EntryType.INCOME, auth, session);
            }
        });
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals(before, testAccount.getAmount());
        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    public void rejectsGlobalCategoryOnCreate() {
        testCategory.setUser(null);

        assertForbiddenWithoutWrites(false);
    }

    @Test
    public void rejectsMissingAccountBeforeWriting() {
        testEntry.setAccount(null);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> entryController.saveNewEntry(testEntry, EntryType.INCOME, auth, session));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    public void testMoveExpenseToAnotherAccount() {
        // The current balance already includes the original expense
        testEntry.setType(EntryType.EXPENSE);
        testEntry.setAmount(new BigDecimal("50.00"));
        testCategory.setType(EntryType.EXPENSE);
        testAccount.setAmount(new BigDecimal("950.00"));

        Account secondAccount = new Account(
            "Second account",
            new BigDecimal("500.00"),
            "Another account",
            testUser
        );

        ReflectionTestUtils.setField(secondAccount, "account_id", 11L);

        when(accountRepository.findById(11L))
            .thenReturn(Optional.of(secondAccount));

        Entry editedEntry = new Entry(
            "Moved expense",
            EntryType.EXPENSE,
            new BigDecimal("50.00"),
            LocalDateTime.now(),
            "Moved to another account",
            testCategory,
            secondAccount,
            testUser
        );

        entryController.saveEditedEntry(1L, editedEntry, auth, session);

        assertEquals(new BigDecimal("1000.00"), testAccount.getAmount());
        assertEquals(new BigDecimal("450.00"), secondAccount.getAmount());
        assertSame(secondAccount, testEntry.getAccount());

        verify(accountRepository).save(testAccount);
        verify(accountRepository).save(secondAccount);
        verify(entryRepository).save(testEntry);
    }

    @Test
    public void rejectsInvalidAmountsOnCreateAndEdit() {
        assertInvalidAmountRejected(null);
        assertInvalidAmountRejected(BigDecimal.ZERO);
        assertInvalidAmountRejected(new BigDecimal("-50.00"));
        assertInvalidAmountRejected(new BigDecimal("25.001"));
    }

    private void assertInvalidAmountRejected(BigDecimal amount) {
        testEntry.setAmount(amount);
        BigDecimal balanceBefore = testAccount.getAmount();

        ResponseStatusException createException = assertThrows(
            ResponseStatusException.class,
            () -> entryController.saveNewEntry(
                testEntry, EntryType.INCOME, auth, session
            )
        );

        ResponseStatusException editException = assertThrows(
            ResponseStatusException.class,
            () -> entryController.saveEditedEntry(
                1L, testEntry, auth, session
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, createException.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, editException.getStatusCode());
        assertEquals(balanceBefore, testAccount.getAmount());

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    public void rejectsMissingOrBlankTitle() {
        testEntry.setTitle(null);
        assertInvalidEntryRejected();

        testEntry.setTitle("");
        assertInvalidEntryRejected();

        testEntry.setTitle("   ");
        assertInvalidEntryRejected();
    }

    @Test
    public void rejectsMissingDateTime() {
        testEntry.setDateTime(null);
        assertInvalidEntryRejected();
    }

    private void assertInvalidEntryRejected() {
        BigDecimal balanceBefore = testAccount.getAmount();

        ResponseStatusException createException = assertThrows(
            ResponseStatusException.class,
            () -> entryController.saveNewEntry(
                testEntry, EntryType.INCOME, auth, session
            )
        );

        ResponseStatusException editException = assertThrows(
            ResponseStatusException.class,
            () -> entryController.saveEditedEntry(
                1L, testEntry, auth, session
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, createException.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, editException.getStatusCode());
        assertEquals(balanceBefore, testAccount.getAmount());

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    public void rejectsEditingForeignEntryWithoutWrites() {
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("other_user");

        testEntry.setUser(otherUser);
        testAccount.setUser(otherUser);
        BigDecimal balanceBefore = testAccount.getAmount();

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> entryController.saveEditedEntry(
                1L, testEntry, auth, session
            )
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals(balanceBefore, testAccount.getAmount());

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    public void deletingExpenseRestoresBalance() {
        testEntry.setType(EntryType.EXPENSE);
        testEntry.setAmount(new BigDecimal("50"));
        testAccount.setAmount(new BigDecimal("950"));

        String result = entryController.deleteEntry(
            1L, EntryType.EXPENSE, auth
        );

        assertEquals("redirect:/entries?type=EXPENSE", result);
        assertEquals(new BigDecimal("1000"), testAccount.getAmount());

        verify(accountRepository).save(testAccount);
        verify(entryRepository).delete(testEntry);
    }

    @Test
    public void deletingIncomeReversesBalance() {
        testEntry.setType(EntryType.INCOME);
        testEntry.setAmount(new BigDecimal("50"));
        testAccount.setAmount(new BigDecimal("1050"));

        String result = entryController.deleteEntry(
            1L, null, auth
        );

        assertEquals("redirect:/entries", result);
        assertEquals(new BigDecimal("1000"), testAccount.getAmount());

        verify(accountRepository).save(testAccount);
        verify(entryRepository).delete(testEntry);
    }

    @Test
    public void rejectsDeletingForeignEntryWithoutWrites() {
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("other_user");

        testEntry.setUser(otherUser);
        testAccount.setUser(otherUser);
        BigDecimal balanceBefore = testAccount.getAmount();

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> entryController.deleteEntry(1L, null, auth)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals(balanceBefore, testAccount.getAmount());

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).delete(any(Entry.class));
    }

    @Test
    public void rejectsEntryIdOnCreateWithoutWrites() {
        ReflectionTestUtils.setField(testEntry, "id", 99L);
        BigDecimal balanceBefore = testAccount.getAmount();

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> entryController.saveNewEntry(
                testEntry, EntryType.INCOME, auth, session
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(balanceBefore, testAccount.getAmount());

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
    }
}
