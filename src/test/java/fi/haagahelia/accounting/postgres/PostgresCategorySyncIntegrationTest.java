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

import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresCategorySyncIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
}