package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.RegistrationService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private RegistrationService registrationService;

    @Test
    void registersUserWithEncodedPasswordAndUserRole() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "api_registered_user",
                      "password": "test-password",
                      "passwordConfirm": "test-password"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("api_registered_user"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.roles").doesNotExist());

        User saved = userRepository.findByUsername("api_registered_user");

        assertNotNull(saved);
        assertNotEquals("test-password", saved.getPassword());
        assertTrue(passwordEncoder.matches(
            "test-password", saved.getPassword()
        ));
        assertEquals(1, saved.getRoles().size());
        assertEquals(
            "ROLE_USER",
            saved.getRoles().iterator().next().getName()
        );

        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "api_registered_user")
                .param("password", "test-password"))
            .andExpect(status().isOk());

        List<Category> categories = categoryRepository.findByUser(saved);

        assertEquals(19, categories.size());

        assertEquals(
            14L,
            categories.stream()
                .filter(category -> category.getType() == EntryType.EXPENSE)
                .count()
        );

        assertEquals(
            5L,
            categories.stream()
                .filter(category -> category.getType() == EntryType.INCOME)
                .count()
        );

        assertEquals(
            19L,
            categories.stream()
                .map(category -> category.getName())
                .distinct()
                .count()
        );

        assertTrue(categories.stream().allMatch(category ->
            !category.isGlobal()
                && saved.getId().equals(category.getUser().getId())
        ));
    }

    @Test
    void rejectsMismatchedPasswordsWithoutCreatingUser() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "api_rejected_user",
                      "password": "test-password",
                      "passwordConfirm": "different-password"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Passwords do not match"));

        assertNull(userRepository.findByUsername("api_rejected_user"));
    }

    @Test
    void rejectsDuplicateUsernameWithoutChangingExistingUser() throws Exception {
        String username = "api_duplicate_user";

        mockMvc.perform(post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "username": "api_duplicate_user",
                    "password": "original-password",
                    "passwordConfirm": "original-password"
                    }
                    """))
            .andExpect(status().isCreated());

        User original = userRepository.findByUsername(username);
        Long originalId = original.getId();
        String originalPasswordHash = original.getPassword();
        long usersBefore = userRepository.count();
        long categoriesBefore = categoryRepository.count();

        mockMvc.perform(post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "username": "api_duplicate_user",
                    "password": "replacement-password",
                    "passwordConfirm": "replacement-password"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.message").value(
                "This username already exists"
            ));

        User unchanged = userRepository.findByUsername(username);

        assertEquals(usersBefore, userRepository.count());
        assertEquals(originalId, unchanged.getId());
        assertEquals(originalPasswordHash, unchanged.getPassword());
        assertEquals(categoriesBefore, categoryRepository.count());

        assertTrue(passwordEncoder.matches(
            "original-password", unchanged.getPassword()
        ));
        assertFalse(passwordEncoder.matches(
            "replacement-password", unchanged.getPassword()
        ));

        assertEquals(1, unchanged.getRoles().size());
        assertEquals(
            "ROLE_USER",
            unchanged.getRoles().iterator().next().getName()
        );
    }

    @Test
    void deletingDefaultCategoryDoesNotAffectAnotherUser()
            throws Exception {

        User first = registrationService.register(
            "default_category_first",
            "test-password",
            "test-password"
        );

        User second = registrationService.register(
            "default_category_second",
            "test-password",
            "test-password"
        );

        Category firstGroceries = categoryRepository.findByUser(first)
            .stream()
            .filter(category ->
                category.getName().equals("Groceries")
                    && category.getType() == EntryType.EXPENSE
            )
            .findFirst()
            .orElseThrow();

        Category secondGroceries = categoryRepository.findByUser(second)
            .stream()
            .filter(category ->
                category.getName().equals("Groceries")
                    && category.getType() == EntryType.EXPENSE
            )
            .findFirst()
            .orElseThrow();

        assertNotEquals(
            firstGroceries.getCategory_id(),
            secondGroceries.getCategory_id()
        );

        // Even matching default categories belong to different users.
        mockMvc.perform(delete(
                    "/api/categories/{id}",
                    secondGroceries.getCategory_id()
                )
                .with(user(first.getUsername()))
                .with(csrf()))
            .andExpect(status().isForbidden());

        // The user's own unused default category can be deleted.
        mockMvc.perform(delete(
                    "/api/categories/{id}",
                    firstGroceries.getCategory_id()
                )
                .with(user(first.getUsername()))
                .with(csrf()))
            .andExpect(status().isNoContent());

        categoryRepository.flush();

        assertFalse(
            categoryRepository.existsById(firstGroceries.getCategory_id())
        );

        assertTrue(
            categoryRepository.existsById(secondGroceries.getCategory_id())
        );

        assertEquals(18, categoryRepository.findByUser(first).size());
        assertEquals(19, categoryRepository.findByUser(second).size());
    }
}