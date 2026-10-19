package fi.haagahelia.accounting.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CategoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void returnsOnlyOwnCategories() throws Exception {
        User owner = createUser("api_category_owner");
        User other = createUser("api_category_other");

        categoryRepository.save(
            new Category("API own expense", EntryType.EXPENSE, owner)
        );
        categoryRepository.save(
            new Category("API global expense", EntryType.EXPENSE, null)
        );
        categoryRepository.save(
            new Category("API foreign expense", EntryType.EXPENSE, other)
        );
        categoryRepository.save(
            new Category("API own income", EntryType.INCOME, owner)
        );

        mockMvc.perform(get("/api/categories")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem("API own expense")))
            .andExpect(jsonPath(
                "$[*].name", not(hasItem("API global expense"))
            ))
            .andExpect(jsonPath("$[*].name", hasItem("API own income")))
            .andExpect(jsonPath(
                "$[*].name", not(hasItem("API foreign expense"))
            ));

        mockMvc.perform(get("/api/categories")
                .param("type", "EXPENSE")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem("API own expense")))
            .andExpect(jsonPath(
                "$[*].name", not(hasItem("API global expense"))
            ))
            .andExpect(jsonPath(
                "$[*].name", not(hasItem("API foreign expense"))
            ))
            .andExpect(jsonPath(
                "$[*].type", not(hasItem("INCOME"))
            ));
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused-in-this-test");

        return userRepository.save(user);
    }

    @Test
    void createsPersonalCategoryForAuthenticatedUser() throws Exception {
        User owner = createUser("api_create_category_owner");

        mockMvc.perform(post("/api/categories")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Transport",
                    "type": "EXPENSE"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Transport"))
            .andExpect(jsonPath("$.type").value("EXPENSE"))
            .andExpect(jsonPath("$.global").value(false))
            .andExpect(jsonPath("$.user").doesNotExist());

        var categories = categoryRepository.findByUser(owner);

        assertEquals(1, categories.size());

        Category saved = categories.get(0);
        assertNotNull(saved.getSyncId());
        assertEquals(owner.getId(), saved.getUser().getId());
        assertEquals(EntryType.EXPENSE, saved.getType());
        assertFalse(saved.isGlobal());
    }

    @Test
    void rejectsCategoryWithoutType() throws Exception {
        User owner = createUser("api_invalid_category_owner");

        mockMvc.perform(post("/api/categories")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Transport"
                    }
                    """))
            .andExpect(status().isBadRequest());

        assertEquals(0, categoryRepository.findByUser(owner).size());
    }

    @Test
    void deletesOwnEmptyCategory() throws Exception {
        User owner = createUser("api_delete_category_owner");

        Category category = categoryRepository.save(
            new Category("Transport", EntryType.EXPENSE, owner)
        );

        mockMvc.perform(delete(
                    "/api/categories/{id}", category.getCategory_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        assertTrue(
            categoryRepository.findById(category.getCategory_id()).isEmpty()
        );
    }

    @Test
    void rejectsDeletingForeignCategory() throws Exception {
        User owner = createUser("api_category_actual_owner");
        User other = createUser("api_category_delete_other");

        Category category = categoryRepository.save(
            new Category("Private category", EntryType.EXPENSE, owner)
        );

        mockMvc.perform(delete(
                    "/api/categories/{id}", category.getCategory_id()
                )
                .with(user(other.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(
            categoryRepository.findById(category.getCategory_id()).isPresent()
        );
    }

    @Test
    void rejectsDeletingGlobalCategory() throws Exception {
        User owner = createUser("api_global_category_delete_user");

        Category category = categoryRepository.save(
            new Category("Global category", EntryType.EXPENSE, null)
        );

        mockMvc.perform(delete(
                    "/api/categories/{id}", category.getCategory_id()
                )
                .with(user(owner.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertTrue(
            categoryRepository.findById(category.getCategory_id()).isPresent()
        );
    }

    @Test
    void createsAndReturnsCategoryWithClientSyncId() throws Exception {
        User owner = createUser("api_category_sync_owner");
        UUID syncId = UUID.randomUUID();

        mockMvc.perform(post("/api/categories")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Offline category",
                        "type": "EXPENSE",
                        "syncId": "%s"
                    }
                    """.formatted(syncId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()));

        categoryRepository.flush();

        var categories = categoryRepository.findByUser(owner);

        assertEquals(1, categories.size());
        assertEquals(syncId, categories.get(0).getSyncId());
        assertEquals(owner.getId(), categories.get(0).getUser().getId());

        mockMvc.perform(get("/api/categories")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].syncId").value(syncId.toString()));
    }

    @Test
    void rejectsDuplicateCategorySyncIdWithoutChangingCategory()
            throws Exception {

        User owner = createUser("api_category_duplicate_sync_owner");

        Category existing = categoryRepository.saveAndFlush(
            new Category("Original category", EntryType.EXPENSE, owner)
        );

        mockMvc.perform(post("/api/categories")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Duplicate category",
                        "type": "INCOME",
                        "syncId": "%s"
                    }
                    """.formatted(existing.getSyncId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message")
                .value("Category sync ID is already in use"));

        var categories = categoryRepository.findByUser(owner);

        assertEquals(1, categories.size());
        assertEquals(
            existing.getCategory_id(),
            categories.get(0).getCategory_id()
        );
        assertEquals("Original category", categories.get(0).getName());
        assertEquals(EntryType.EXPENSE, categories.get(0).getType());
    }

    @Test
    void rejectsForeignCategorySyncIdWithoutChangingCategories()
            throws Exception {

        User owner = createUser("api_category_sync_actual_owner");
        User otherUser = createUser("api_category_sync_other_user");

        Category existing = categoryRepository.saveAndFlush(
            new Category("Private category", EntryType.EXPENSE, owner)
        );

        mockMvc.perform(post("/api/categories")
                .with(user(otherUser.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Attempted duplicate",
                        "type": "INCOME",
                        "syncId": "%s"
                    }
                    """.formatted(existing.getSyncId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message")
                .value("Category sync ID is already in use"))
            .andExpect(jsonPath("$.name").doesNotExist())
            .andExpect(jsonPath("$.type").doesNotExist())
            .andExpect(jsonPath("$.user").doesNotExist());

        assertEquals(
            0,
            categoryRepository.findByUser(otherUser).size()
        );

        var ownerCategories = categoryRepository.findByUser(owner);

        assertEquals(1, ownerCategories.size());

        Category unchanged = ownerCategories.get(0);

        assertEquals(
            existing.getCategory_id(),
            unchanged.getCategory_id()
        );
        assertEquals("Private category", unchanged.getName());
        assertEquals(EntryType.EXPENSE, unchanged.getType());
        assertEquals(owner.getId(), unchanged.getUser().getId());
    }
}