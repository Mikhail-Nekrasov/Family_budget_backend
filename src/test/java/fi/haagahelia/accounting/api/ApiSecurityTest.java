package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    void anonymousApiRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Location"))
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").value(
                "Authentication is required"
            ));
    }

    @Test
    void anonymousWebRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/accounts")
                .accept(MediaType.TEXT_HTML))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    void apiRequestWithoutCsrfReturnsJsonForbidden() throws Exception {
        mockMvc.perform(post("/api/accounts")
                .with(user("security_test_user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Cash",
                    "amount": 100
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void anonymousUserCanGetCsrfToken() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"));
    }

    @Test
    @Transactional
    void loginCreatesSessionForSubsequentApiRequests() throws Exception {
        User account = new User();
        account.setUsername("api_login_test_user");
        account.setPassword(passwordEncoder.encode("test-password"));
        account.setRoles(Set.of());

        userRepository.saveAndFlush(account);

        var result = mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "api_login_test_user")
                .param("password", "test-password"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.username").value("api_login_test_user"))
            .andReturn();

        MockHttpSession session =
            (MockHttpSession) result.getRequest().getSession(false);

        assertNotNull(session);
        mockMvc.perform(get("/api/auth/me")
                .session(session)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("api_login_test_user"));

        mockMvc.perform(get("/api/accounts")
                .session(session)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }

    @Test
    void invalidLoginReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "nonexistent_api_login_user")
                .param("password", "wrong-password"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").value(
                "Invalid username or password"
            ));
    }

    @Test
    @Transactional
    void logoutInvalidatesSession() throws Exception {
        User account = new User();
        account.setUsername("api_logout_test_user");
        account.setPassword(passwordEncoder.encode("test-password"));
        account.setRoles(Set.of());

        userRepository.saveAndFlush(account);

        var loginResult = mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "api_logout_test_user")
                .param("password", "test-password"))
            .andExpect(status().isOk())
            .andReturn();

        MockHttpSession session =
            (MockHttpSession) loginResult.getRequest().getSession(false);

        assertNotNull(session);

        mockMvc.perform(post("/api/auth/logout")
                .session(session)
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
            .andExpect(cookie().maxAge("JSESSIONID", 0));

        assertTrue(session.isInvalid());

        mockMvc.perform(get("/api/accounts")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void returnsCurrentAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                .with(user("session_user"))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("session_user"));
    }

    @Test
    void rejectsAnonymousCurrentUserRequest() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").value(
                "Authentication is required"
            ));
    }

    @Test
    @Transactional
    void supportsClientFlowWithRealCsrfTokens() throws Exception {
        User account = new User();
        account.setUsername("api_real_csrf_user");
        account.setPassword(passwordEncoder.encode("test-password"));
        account.setRoles(Set.of());

        userRepository.saveAndFlush(account);

        // Get a CSRF token and the initial session.
        var csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();

        MockHttpSession session =
            (MockHttpSession) csrfResult.getRequest().getSession(false);

        assertNotNull(session);

        var csrfBody = objectMapper.readTree(
            csrfResult.getResponse().getContentAsString()
        );

        // Log in using the token returned by the API.
        mockMvc.perform(post("/api/auth/login")
                .session(session)
                .header(
                    csrfBody.get("headerName").asString(),
                    csrfBody.get("token").asString()
                )
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "api_real_csrf_user")
                .param("password", "test-password"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("api_real_csrf_user"));

        // Authentication clears the old CSRF token; obtain a new one.
        var authenticatedCsrfResult = mockMvc.perform(get("/api/auth/csrf")
                .session(session))
            .andExpect(status().isOk())
            .andReturn();

        var authenticatedCsrfBody = objectMapper.readTree(
            authenticatedCsrfResult.getResponse().getContentAsString()
        );

        String headerName = authenticatedCsrfBody
            .get("headerName").asString();

        String token = authenticatedCsrfBody
            .get("token").asString();

        // Create an account using the authenticated session and new token.
        mockMvc.perform(post("/api/accounts")
                .session(session)
                .header(headerName, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Client flow account",
                    "amount": 100.00,
                    "description": "Created with a real CSRF token"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Client flow account"));

        mockMvc.perform(get("/api/accounts")
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Client flow account"));

        // Log out using the same session and valid token.
        mockMvc.perform(post("/api/auth/logout")
                .session(session)
                .header(headerName, token))
            .andExpect(status().isNoContent());

        assertTrue(session.isInvalid());
    }
}