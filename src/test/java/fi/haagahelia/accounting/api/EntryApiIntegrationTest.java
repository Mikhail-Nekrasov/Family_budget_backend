package fi.haagahelia.accounting.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
}