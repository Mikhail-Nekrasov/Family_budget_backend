package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountSyncService;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
@AutoConfigureMockMvc
class PostgresAccountSyncIntegrationTest {

    @Autowired
    private AccountSyncService accountSyncService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private User owner;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("account_sync_test_owner");
        user.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(user);
    }

    @Test
    void repeatedCommandReturnsOriginalResponseWithoutCreatingAnotherAccount() {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        CreateAccountRequest request = new CreateAccountRequest(
            "Cash",
            new BigDecimal("100.00"),
            "Personal account",
            syncId
        );

        CompletedCommand first = accountSyncService.createAccount(
            commandId, request, owner
        );

        // A numerically equivalent amount is the same command payload.
        CreateAccountRequest repeatedRequest = new CreateAccountRequest(
            "Cash",
            new BigDecimal("100.0"),
            "Personal account",
            syncId
        );

        CompletedCommand repeated = accountSyncService.createAccount(
            commandId, repeatedRequest, owner
        );

        accountRepository.flush();

        assertEquals(201, first.responseStatus());
        assertEquals(first, repeated);

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals(syncId, accounts.get(0).getSyncId());
        assertEquals(
            0,
            new BigDecimal("100.00")
                .compareTo(accounts.get(0).getAmount())
        );

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ?
                """,
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void rejectsSameCommandIdWithDifferentPayload() {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        accountSyncService.createAccount(
            commandId,
            new CreateAccountRequest(
                "Cash",
                BigDecimal.ZERO,
                "",
                syncId
            ),
            owner
        );

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> accountSyncService.createAccount(
                commandId,
                new CreateAccountRequest(
                    "Changed name",
                    BigDecimal.ZERO,
                    "",
                    syncId
                ),
                owner
            )
        );

        assertEquals(
            HttpStatus.CONFLICT,
            exception.getStatusCode()
        );
        assertEquals(
            "Command ID is already used for a different request",
            exception.getReason()
        );

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals("Cash", accounts.get(0).getName());

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ?
                """,
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void repeatedHttpRequestReturnsExactlyTheSameResponse()
            throws Exception {

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        String requestBody = """
            {
                "name": "Offline cash",
                "amount": 100,
                "description": "Created on phone",
                "syncId": "%s"
            }
            """.formatted(syncId);

        String firstResponse = mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        assertEquals(
            1,
            accountRepository.findByUser(owner).size()
        );
    }

    @Test
    void rejectsSyncRequestWithoutSyncId() throws Exception {
        UUID commandId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Missing sync ID",
                            "amount": 0,
                            "description": ""
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "Sync ID is required for synchronization"
            ));

        assertEquals(
            0,
            accountRepository.findByUser(owner).size()
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE user_id = ?
                """,
                Long.class,
                owner.getId()
            )
        );
    }

    @Test
    void rejectsAnonymousSyncRequest() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Anonymous account",
                            "amount": 0,
                            "description": "",
                            "syncId": "%s"
                        }
                        """.formatted(syncId))
            )
            .andExpect(status().isUnauthorized());

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_command
                WHERE command_id = ?
                """,
                Long.class,
                commandId
            )
        );

        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE sync_id = ?",
                Long.class,
                syncId
            )
        );
    }
}