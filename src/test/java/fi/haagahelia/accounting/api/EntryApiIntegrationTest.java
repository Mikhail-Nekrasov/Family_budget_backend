package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EntryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EntryRepository entryRepository;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = createUser("api_entry_owner");
        User other = createUser("api_entry_other");

        createEntry(
            owner, "Older expense", EntryType.EXPENSE,
            LocalDateTime.of(2026, 1, 1, 12, 0)
        );
        createEntry(
            owner, "Newer income", EntryType.INCOME,
            LocalDateTime.of(2026, 1, 2, 12, 0)
        );
        createEntry(
            other, "Foreign entry", EntryType.EXPENSE,
            LocalDateTime.of(2026, 1, 3, 12, 0)
        );
    }

    @Test
    void returnsOnlyOwnEntriesWithRelatedNames() throws Exception {
        mockMvc.perform(get("/api/entries")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].title").value("Newer income"))
            .andExpect(jsonPath("$.content[1].title").value("Older expense"))
            .andExpect(jsonPath("$.content[0].accountId").isNumber())
            .andExpect(jsonPath("$.content[0].accountName").value("Test account"))
            .andExpect(jsonPath("$.content[0].categoryId").isNumber())
            .andExpect(jsonPath("$.content[0].categoryName").value("Test INCOME"))
            .andExpect(jsonPath("$.content[0].user").doesNotExist());
    }

    @Test
    void filtersEntriesByType() throws Exception {
        mockMvc.perform(get("/api/entries")
                .param("type", "EXPENSE")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Older expense"))
            .andExpect(jsonPath("$.content[0].type").value("EXPENSE"));
    }

    @Test
    void returnsRequestedPages() throws Exception {
        mockMvc.perform(get("/api/entries")
                .param("page", "0")
                .param("size", "1")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Newer income"));

        mockMvc.perform(get("/api/entries")
                .param("page", "1")
                .param("size", "1")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Older expense"));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/api/entries")
                .param("page", "-1")
                .with(user(owner.getUsername())))
            .andExpect(status().isBadRequest());

        for (String size : new String[] {"0", "101"}) {
            mockMvc.perform(get("/api/entries")
                    .param("size", size)
                    .with(user(owner.getUsername())))
                .andExpect(status().isBadRequest());
        }
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused-in-this-test");

        return userRepository.save(user);
    }

    private void createEntry(User user, String title,
                             EntryType type, LocalDateTime dateTime) {
        Account account = accountRepository.save(
            new Account("Test account", BigDecimal.ZERO, "", user)
        );

        Category category = categoryRepository.save(
            new Category("Test " + type, type, user)
        );

        entryRepository.save(
            new Entry(
                title,
                type,
                new BigDecimal("10.00"),
                dateTime,
                "Test description",
                category,
                account,
                user
            )
        );
    }

    @Test
    void createsExpenseAndUpdatesAccountBalance() throws Exception {
        Account account = accountRepository.save(
            new Account(
                "API expense account",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        Category category = categoryRepository.save(
            new Category("API food", EntryType.EXPENSE, owner)
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(post("/api/entries")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "title": "Lunch",
                    "type": "EXPENSE",
                    "amount": 25.00,
                    "dateTime": "2026-10-12T12:00:00",
                    "description": "Cafe",
                    "accountId": %d,
                    "categoryId": %d
                    }
                    """.formatted(
                        account.getAccount_id(),
                        category.getCategory_id()
                    )))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.title").value("Lunch"))
            .andExpect(jsonPath("$.type").value("EXPENSE"))
            .andExpect(jsonPath("$.amount").value(25.00))
            .andExpect(jsonPath("$.accountName").value("API expense account"))
            .andExpect(jsonPath("$.categoryName").value("API food"))
            .andExpect(jsonPath("$.user").doesNotExist());

        Account updated = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(updated.getAmount())
        );
        assertEquals(entriesBefore + 1, entryRepository.count());
    }

    @Test
    void rejectsCreatingEntryWithForeignAccountWithoutChanges() throws Exception {
        User other = createUser("api_foreign_account_owner");

        Account foreignAccount = accountRepository.save(
            new Account(
                "Foreign account",
                new BigDecimal("100.00"),
                "",
                other
            )
        );

        Category ownCategory = categoryRepository.save(
            new Category("Own food category", EntryType.EXPENSE, owner)
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(post("/api/entries")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "title": "Lunch",
                    "type": "EXPENSE",
                    "amount": 25.00,
                    "dateTime": "2026-10-12T12:00:00",
                    "description": "Must be rejected",
                    "accountId": %d,
                    "categoryId": %d
                    }
                    """.formatted(
                        foreignAccount.getAccount_id(),
                        ownCategory.getCategory_id()
                    )))
            .andExpect(status().isForbidden());

        Account unchanged = accountRepository
            .findById(foreignAccount.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(unchanged.getAmount())
        );
        assertEquals(entriesBefore, entryRepository.count());
    }

    @Test
    void rejectsCreatingEntryWithForeignCategoryWithoutChanges() throws Exception {
        User other = createUser("api_foreign_category_owner");

        Account ownAccount = accountRepository.save(
            new Account(
                "Own account",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        Category foreignCategory = categoryRepository.save(
            new Category("Private food category", EntryType.EXPENSE, other)
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(post("/api/entries")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "title": "Lunch",
                    "type": "EXPENSE",
                    "amount": 25.00,
                    "dateTime": "2026-10-12T12:00:00",
                    "description": "Must be rejected",
                    "accountId": %d,
                    "categoryId": %d
                    }
                    """.formatted(
                        ownAccount.getAccount_id(),
                        foreignCategory.getCategory_id()
                    )))
            .andExpect(status().isForbidden());

        Account unchanged = accountRepository
            .findById(ownAccount.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(unchanged.getAmount())
        );
        assertEquals(entriesBefore, entryRepository.count());
    }

    @Test
    void updatesExpenseAndRecalculatesBalance() throws Exception {
        // Balance already includes the original expense of 50.
        Account account = accountRepository.save(
            new Account(
                "Edit account",
                new BigDecimal("950.00"),
                "",
                owner
            )
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Original expense",
                EntryType.EXPENSE,
                new BigDecimal("50.00"),
                LocalDateTime.of(2026, 10, 12, 12, 0),
                "",
                category,
                account,
                owner
            )
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
                    "amount": 200.00,
                    "dateTime": "2026-10-12T13:00:00",
                    "description": "Updated",
                    "accountId": %d,
                    "categoryId": %d
                    }
                    """.formatted(
                        account.getAccount_id(),
                        category.getCategory_id()
                    )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(entry.getId().intValue()))
            .andExpect(jsonPath("$.title").value("Updated expense"))
            .andExpect(jsonPath("$.amount").value(200.00));

        Account updatedAccount = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        Entry updatedEntry = entryRepository
            .findById(entry.getId())
            .orElseThrow();

        // 950 + 50 - 200 = 800
        assertEquals(
            0,
            new BigDecimal("800.00").compareTo(updatedAccount.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("200.00").compareTo(updatedEntry.getAmount())
        );
        assertEquals("Updated expense", updatedEntry.getTitle());
        assertEquals(entriesBefore, entryRepository.count());
    }

    @Test
    void deletesExpenseAndRestoresBalance() throws Exception {
        Account account = accountRepository.save(
            new Account(
                "Delete account",
                new BigDecimal("950.00"),
                "",
                owner
            )
        );

        Category category = categoryRepository.save(
            new Category("Food", EntryType.EXPENSE, owner)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Expense to delete",
                EntryType.EXPENSE,
                new BigDecimal("50.00"),
                LocalDateTime.of(2026, 10, 12, 12, 0),
                "",
                category,
                account,
                owner
            )
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(delete("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        Account updatedAccount = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("1000.00").compareTo(updatedAccount.getAmount())
        );
        assertTrue(entryRepository.findById(entry.getId()).isEmpty());
        assertEquals(entriesBefore - 1, entryRepository.count());
    }

    @Test
    void rejectsDeletingForeignEntryWithoutChanges() throws Exception {
        User other = createUser("api_delete_entry_other");

        Account account = accountRepository.save(
            new Account(
                "Foreign account",
                new BigDecimal("950.00"),
                "",
                other
            )
        );

        Category category = categoryRepository.save(
            new Category("Private food", EntryType.EXPENSE, other)
        );

        Entry entry = entryRepository.save(
            new Entry(
                "Foreign expense",
                EntryType.EXPENSE,
                new BigDecimal("50.00"),
                LocalDateTime.of(2026, 10, 12, 12, 0),
                "",
                category,
                account,
                other
            )
        );

        long entriesBefore = entryRepository.count();

        mockMvc.perform(delete("/api/entries/{id}", entry.getId())
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        Account unchanged = accountRepository
            .findById(account.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("950.00").compareTo(unchanged.getAmount())
        );
        assertTrue(entryRepository.findById(entry.getId()).isPresent());
        assertEquals(entriesBefore, entryRepository.count());
    }

    @Test
    void returnsSummaryWithoutForeignEntries() throws Exception {
        mockMvc.perform(get("/api/entries/summary")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalIncome").value(10.00))
            .andExpect(jsonPath("$.totalExpense").value(10.00))
            .andExpect(jsonPath("$.netAmount").value(0));
    }

    @Test
    void returnsZeroSummaryWhenUserHasNoEntries() throws Exception {
        User emptyUser = createUser("api_empty_summary_user");

        mockMvc.perform(get("/api/entries/summary")
                .with(user(emptyUser.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalIncome").value(0))
            .andExpect(jsonPath("$.totalExpense").value(0))
            .andExpect(jsonPath("$.netAmount").value(0));
    }

    @Test
    void returnsJsonErrorForInvalidEntryType() throws Exception {
        mockMvc.perform(get("/api/entries")
                .param("type", "UNKNOWN")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value(
                "Invalid value for parameter: type"
            ));
    }
}