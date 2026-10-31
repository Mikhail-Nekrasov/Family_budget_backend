package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.api.dto.UpdateAccountOrderRequest;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserAccountOrderRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresAccountOrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserAccountOrderRepository orderRepository;

    @Autowired
    private JsonMapper jsonMapper;

    private User owner;
    private User other;
    private Account firstAccount;
    private Account secondAccount;

    @BeforeEach
    void setUp() {
        owner = createUser();
        other = createUser();

        firstAccount = accountRepository.saveAndFlush(
            new Account("First", BigDecimal.ZERO, null, owner)
        );

        secondAccount = accountRepository.saveAndFlush(
            new Account("Second", BigDecimal.ZERO, null, owner)
        );
    }

    private User createUser() {
        User created = new User();
        created.setUsername("order_api_" + UUID.randomUUID());
        created.setPassword("unused-in-this-test");

        return userRepository.saveAndFlush(created);
    }

    private MockHttpServletRequestBuilder saveRequest(
            User currentUser,
            UUID commandId,
            List<UUID> syncIds,
            long version) {

        return put("/api/sync/account-order/{commandId}", commandId)
            .with(user(currentUser.getUsername()))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(
                new UpdateAccountOrderRequest(syncIds, version)
            ));
    }

    @Test
    void returnsEmptyOrderBeforeFirstSave() throws Exception {
        mockMvc.perform(get("/api/account-order")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountSyncIds").isEmpty())
            .andExpect(jsonPath("$.version").value(0));

        assertTrue(
            orderRepository.findVersion(owner.getId()).isEmpty()
        );
    }

    @Test
    void savesAndReadsRequestedOrder() throws Exception {
        mockMvc.perform(saveRequest(
                owner,
                UUID.randomUUID(),
                List.of(
                    secondAccount.getSyncId(),
                    firstAccount.getSyncId()
                ),
                0L
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/account-order")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.accountSyncIds.length()").value(2))
            .andExpect(jsonPath("$.accountSyncIds[0]").value(
                secondAccount.getSyncId().toString()
            ))
            .andExpect(jsonPath("$.accountSyncIds[1]").value(
                firstAccount.getSyncId().toString()
            ));
    }

    @Test
    void repeatingCommandReturnsSameResponse() throws Exception {
        UUID commandId = UUID.randomUUID();
        List<UUID> syncIds = List.of(firstAccount.getSyncId());

        String firstResponse = mockMvc.perform(
                saveRequest(owner, commandId, syncIds, 0L))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        mockMvc.perform(saveRequest(owner, commandId, syncIds, 0L))
            .andExpect(status().isOk())
            .andExpect(content().string(firstResponse));

        mockMvc.perform(get("/api/account-order")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anotherUserDoesNotReceiveOwnersOrder() throws Exception {
        mockMvc.perform(saveRequest(
                owner,
                UUID.randomUUID(),
                List.of(firstAccount.getSyncId()),
                0L
            ))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/account-order")
                .with(user(other.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountSyncIds").isEmpty())
            .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void rejectsAccountWithoutAccess() throws Exception {
        mockMvc.perform(saveRequest(
                other,
                UUID.randomUUID(),
                List.of(firstAccount.getSyncId()),
                0L
            ))
            .andExpect(status().isNotFound());

        assertTrue(
            orderRepository.findVersion(other.getId()).isEmpty()
        );
    }

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/account-order"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(
                put("/api/sync/account-order/{commandId}", UUID.randomUUID())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "accountSyncIds": [],
                          "expectedVersion": 0
                        }
                        """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWriteWithoutCsrfToken() throws Exception {
        mockMvc.perform(
                put("/api/sync/account-order/{commandId}", UUID.randomUUID())
                    .with(user(owner.getUsername()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "accountSyncIds": [],
                          "expectedVersion": 0
                        }
                        """))
            .andExpect(status().isForbidden());

        assertTrue(
            orderRepository.findVersion(owner.getId()).isEmpty()
        );
    }
}