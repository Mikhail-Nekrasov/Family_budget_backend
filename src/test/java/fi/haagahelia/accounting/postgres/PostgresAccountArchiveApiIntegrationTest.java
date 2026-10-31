package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresAccountArchiveApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private User owner;
    private Account account;

    @BeforeEach
    void setUp() {
        owner = createUser();

        account = accountRepository.saveAndFlush(
            new Account(
                "Cash",
                new BigDecimal("100.00"),
                "Personal account",
                owner
            )
        );
    }

    private User createUser() {
        User created = new User();
        created.setUsername("archive_test_" + UUID.randomUUID());
        created.setPassword("unused-in-this-test");

        return userRepository.saveAndFlush(created);
    }

    private String requestBody(long version, boolean archived) {
        return """
            {
                "syncId": "%s",
                "expectedVersion": %d,
                "archived": %s
            }
            """.formatted(
                account.getSyncId(),
                version,
                archived
            );
    }

    @Test
    void archivesAndRestoresWithoutChangingBalance() throws Exception {
        long originalVersion = account.getVersion();

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(originalVersion, true))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archivedAt").isNotEmpty())
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.version").value(originalVersion + 1));

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(originalVersion + 1, false))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archivedAt").value(
                org.hamcrest.Matchers.nullValue()
            ))
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.version").value(originalVersion + 2));

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(account.getAmount())
        );
    }

    @Test
    void retryReturnsOriginalResponseAndStaleCommandIsRejected()
            throws Exception {

        long version = account.getVersion();
        UUID commandId = UUID.randomUUID();
        String body = requestBody(version, true);

        String response = mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    commandId
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    commandId
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isOk())
            .andExpect(content().string(response));

        // An existing command ID cannot acquire a different meaning.
        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    commandId
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(version, false))
            )
            .andExpect(status().isConflict());

        // A new command must use the current account version.
        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(version, false))
            )
            .andExpect(status().isConflict());

        assertEquals(version + 1, account.getVersion().longValue());
    }

    @Test
    void alreadyActiveAccountKeepsItsVersion() throws Exception {
        long version = account.getVersion();

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(version, false))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.archivedAt").value(
                org.hamcrest.Matchers.nullValue()
            ))
            .andExpect(jsonPath("$.version").value(version));
    }

    @Test
    void anotherUserCannotArchiveAccount() throws Exception {
        User other = createUser();

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(other.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(account.getVersion(), true))
            )
            .andExpect(status().isNotFound());
    }

    @Test
    void missingArchivedStateIsRejected() throws Exception {
        String body = """
            {
                "syncId": "%s",
                "expectedVersion": %d
            }
            """.formatted(
                account.getSyncId(),
                account.getVersion()
            );

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        String body = requestBody(account.getVersion(), true);

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isUnauthorized());

        mockMvc.perform(
                post(
                    "/api/sync/accounts/{commandId}/archive-state",
                    UUID.randomUUID()
                )
                    .with(user(owner.getUsername()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isForbidden());
    }
}