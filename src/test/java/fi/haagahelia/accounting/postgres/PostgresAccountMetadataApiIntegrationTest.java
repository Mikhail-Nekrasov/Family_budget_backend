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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.api.dto.CreateAccountRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresAccountMetadataApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JsonMapper jsonMapper;

    private User owner;

    @BeforeEach
    void setUp() {
        User created = new User();
        created.setUsername("account_metadata_" + UUID.randomUUID());
        created.setPassword("unused-in-this-test");

        owner = userRepository.saveAndFlush(created);
    }

    private MockHttpServletRequestBuilder createRequest(
            boolean sync,
            String body) {

        String path = sync
            ? "/api/sync/accounts/" + UUID.randomUUID()
            : "/api/accounts";

        return post(path)
            .with(user(owner.getUsername()))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private String requestBody(String currencyCode, String iconKey) {
        return jsonMapper.writeValueAsString(
            new CreateAccountRequest(
                "Travel account",
                new BigDecimal("100.00"),
                "",
                UUID.randomUUID(),
                currencyCode,
                iconKey
            )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesCurrencyAndArbitraryBuiltInIconKey(boolean sync)
            throws Exception {

        mockMvc.perform(createRequest(
                sync,
                requestBody(" usd ", " travel-fund ")
            ))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.currencyCode").value("USD"))
            .andExpect(jsonPath("$.iconKey").value("travel-fund"));

        var savedAccounts = accountRepository.findByUser(owner);

        assertEquals(1, savedAccounts.size());
        assertEquals("USD", savedAccounts.get(0).getCurrencyCode());
        assertEquals("travel-fund", savedAccounts.get(0).getIconKey());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesDefaultsWhenMetadataIsMissing(boolean sync)
            throws Exception {

        String body = """
            {
              "name": "Cash",
              "amount": 0,
              "syncId": "%s"
            }
            """.formatted(UUID.randomUUID());

        mockMvc.perform(createRequest(sync, body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.currencyCode").value("EUR"))
            .andExpect(jsonPath("$.iconKey").value("cash"));

        var savedAccounts = accountRepository.findByUser(owner);

        assertEquals(1, savedAccounts.size());
        assertEquals("EUR", savedAccounts.get(0).getCurrencyCode());
        assertEquals("cash", savedAccounts.get(0).getIconKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "EU", "ZZZ", "JPY", "KWD"})
    void rejectsInvalidOrUnsupportedCurrency(String currencyCode)
            throws Exception {

        mockMvc.perform(createRequest(
                false,
                requestBody(currencyCode, "cash")
            ))
            .andExpect(status().isBadRequest());

        assertEquals(0, accountRepository.findByUser(owner).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankIconKey(String iconKey) throws Exception {
        mockMvc.perform(createRequest(
                false,
                requestBody("EUR", iconKey)
            ))
            .andExpect(status().isBadRequest());

        assertEquals(0, accountRepository.findByUser(owner).size());
    }

    @Test
    void rejectsIconKeyLongerThanDatabaseLimit() throws Exception {
        mockMvc.perform(createRequest(
                false,
                requestBody("EUR", "a".repeat(65))
            ))
            .andExpect(status().isBadRequest());

        assertEquals(0, accountRepository.findByUser(owner).size());
    }

    @Test
    void rejectsChangedIconWhenCommandIdIsReused() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        String firstBody = jsonMapper.writeValueAsString(
            new CreateAccountRequest(
                "Card",
                BigDecimal.ZERO,
                "",
                syncId,
                "EUR",
                "card"
            )
        );

        String changedBody = jsonMapper.writeValueAsString(
            new CreateAccountRequest(
                "Card",
                BigDecimal.ZERO,
                "",
                syncId,
                "EUR",
                "wallet"
            )
        );

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(firstBody))
            .andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/sync/accounts/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changedBody))
            .andExpect(status().isConflict());
    }
}