package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;

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
}