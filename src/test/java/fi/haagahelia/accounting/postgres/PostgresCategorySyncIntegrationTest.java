package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.repository.AccountRepository;
import java.math.BigDecimal;
import jakarta.persistence.EntityManager;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.CategoryService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresCategorySyncIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryService categoryService;

    private User owner;

    @BeforeEach
    void setUp() {
        User accountOwner = new User();
        accountOwner.setUsername("category_sync_test_owner");
        accountOwner.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(accountOwner);
    }

    @Test
    void repeatedRequestReturnsOriginalResponseWithoutCreatingDuplicate()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        String requestBody = """
            {
                "name": "Offline groceries",
                "type": "EXPENSE",
                "syncId": "%s"
            }
            """.formatted(syncId);

        String firstResponse = mockMvc.perform(
                post("/api/sync/categories/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Offline groceries"))
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                post("/api/sync/categories/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        categoryRepository.flush();

        var categories = categoryRepository.findByUser(owner);

        assertEquals(1, categories.size());
        assertEquals(syncId, categories.get(0).getSyncId());
        assertEquals(EntryType.EXPENSE, categories.get(0).getType());

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                commandId
            )
        );
    }

    @Test
    void rejectsChangedPayloadForCompletedCommand() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/categories/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Original category",
                            "type": "EXPENSE",
                            "syncId": "%s"
                        }
                        """.formatted(syncId))
            )
            .andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/sync/categories/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Changed category",
                            "type": "INCOME",
                            "syncId": "%s"
                        }
                        """.formatted(syncId))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Command ID is already used for a different request"
            ));

        var categories = categoryRepository.findByUser(owner);

        assertEquals(1, categories.size());
        assertEquals("Original category", categories.get(0).getName());
        assertEquals(EntryType.EXPENSE, categories.get(0).getType());
    }

    @Test
    void deletedCategoryCannotBeRecreatedWithTheSameSyncId()
            throws Exception {

        Category category = categoryRepository.saveAndFlush(
            new Category(
                "Temporary category",
                EntryType.EXPENSE,
                owner
            )
        );

        UUID syncId = category.getSyncId();

        categoryService.deleteCategory(
            category.getCategory_id(), owner
        );

        categoryRepository.flush();

        UUID commandId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/categories/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Restored from an old phone",
                            "type": "EXPENSE",
                            "syncId": "%s"
                        }
                        """.formatted(syncId))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                "Category was deleted and cannot be recreated with the same sync ID"
            ));

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_tombstone
                WHERE user_id = ?
                AND entity_type = 'CATEGORY'
                AND sync_id = ?
                """,
                Long.class,
                owner.getId(),
                syncId
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ? AND command_id = ?
                """,
                Long.class,
                owner.getId(),
                commandId
            )
        );
    }
    @Test
    void deletesCategoryOnceAndReturnsOriginalResultOnRetry() throws Exception {
        Category category = categoryRepository.saveAndFlush(
            new Category("Unused", EntryType.EXPENSE, owner)
        );
        UUID commandId = UUID.randomUUID();
        String body = """
            {"syncId":"%s", "expectedVersion":%d}
            """.formatted(category.getSyncId(), category.getVersion());
        String response = mockMvc.perform(
                post("/api/sync/categories/{id}/delete", commandId)
                    .with(user(owner.getUsername())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(true))
            .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/sync/categories/{id}/delete", commandId)
                .with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(content().string(response));
        assertEquals(0L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM category WHERE sync_id = ?", Long.class, category.getSyncId()));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_tombstone WHERE user_id = ? AND entity_type = 'CATEGORY' AND sync_id = ?",
            Long.class, owner.getId(), category.getSyncId()));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_command WHERE user_id = ? AND command_id = ?",
            Long.class, owner.getId(), commandId));
    }

    @Test
    void rejectsStaleCategoryDeletion() throws Exception {
        Category category = categoryRepository.saveAndFlush(
            new Category("Original", EntryType.EXPENSE, owner)
        );
        Long oldVersion = category.getVersion();
        category.setName("Changed");
        categoryRepository.saveAndFlush(category);
        UUID commandId = UUID.randomUUID();
        mockMvc.perform(post("/api/sync/categories/{id}/delete", commandId)
                .with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"syncId":"%s", "expectedVersion":%d}
                    """.formatted(category.getSyncId(), oldVersion)))
            .andExpect(status().isConflict());
        assertEquals(1L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM category WHERE sync_id = ?", Long.class, category.getSyncId()));
        assertEquals(0L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_tombstone WHERE user_id = ? AND entity_type = 'CATEGORY' AND sync_id = ?",
            Long.class, owner.getId(), category.getSyncId()));
        assertEquals(0L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_command WHERE user_id = ? AND command_id = ?",
            Long.class, owner.getId(), commandId));
    }

    @Test
    void cannotDeleteForeignCategoryOrCategoryWithEntries() throws Exception {
        User other = new User();
        other.setUsername("foreign_category_" + UUID.randomUUID());
        other.setPassword("unused");
        other = userRepository.saveAndFlush(other);
        Category foreign = categoryRepository.saveAndFlush(
            new Category("Foreign", EntryType.EXPENSE, other)
        );
        mockMvc.perform(post("/api/sync/categories/{id}/delete", UUID.randomUUID())
                .with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"syncId":"%s", "expectedVersion":%d}
                    """.formatted(foreign.getSyncId(), foreign.getVersion())))
            .andExpect(status().isNotFound());

        Category used = categoryRepository.saveAndFlush(
            new Category("Used", EntryType.EXPENSE, owner)
        );
        Account account = accountRepository.saveAndFlush(
            new Account("Cash", BigDecimal.ZERO, "", owner)
        );
        jdbcTemplate.update("""
            INSERT INTO entry(title, type, amount, date_time, category_id, account_id, user_id)
            VALUES ('Expense', 'EXPENSE', 1, LOCALTIMESTAMP, ?, ?, ?)
            """, used.getCategory_id(), account.getAccount_id(), owner.getId());
        entityManager.clear();
        UUID commandId = UUID.randomUUID();
        mockMvc.perform(post("/api/sync/categories/{id}/delete", commandId)
                .with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"syncId":"%s", "expectedVersion":%d}
                    """.formatted(used.getSyncId(), used.getVersion())))
            .andExpect(status().isConflict());
        assertEquals(0L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_tombstone WHERE user_id = ? AND entity_type = 'CATEGORY' AND sync_id = ?",
            Long.class, owner.getId(), used.getSyncId()));
        assertEquals(0L, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_command WHERE user_id = ? AND command_id = ?",
            Long.class, owner.getId(), commandId));
    }
}
