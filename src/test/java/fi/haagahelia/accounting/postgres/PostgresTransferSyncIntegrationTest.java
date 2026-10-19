package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresTransferSyncIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void repeatedTransferMovesMoneyOnceAndPreservesClientDate()
            throws Exception {

        User owner = new User();
        owner.setUsername("transfer_sync_test_owner");
        owner.setPassword("unused-in-this-test");
        owner = userRepository.saveAndFlush(owner);

        Account source = accountRepository.saveAndFlush(
            new Account(
                "Source", new BigDecimal("100.00"), "", owner
            )
        );

        Account destination = accountRepository.saveAndFlush(
            new Account(
                "Destination", new BigDecimal("100.00"), "", owner
            )
        );

        UUID commandId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();

        LocalDateTime clientDate = LocalDateTime.of(
            2026, 10, 10, 12, 30
        );

        long transfersBefore = transferRepository.count();

        String requestBody = """
            {
                "syncId": "%s",
                "fromAccountSyncId": "%s",
                "toAccountSyncId": "%s",
                "amount": 75.00,
                "dateTime": "2026-10-10T12:30:00",
                "description": "Offline transfer"
            }
            """.formatted(
                syncId,
                source.getSyncId(),
                destination.getSyncId()
            );

        String firstResponse = mockMvc.perform(
                post("/api/sync/transfers/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncId").value(syncId.toString()))
            .andExpect(jsonPath("$.amount").value(75))
            .andReturn()
            .getResponse()
            .getContentAsString();

        transferRepository.flush();

        mockMvc.perform(
                post("/api/sync/transfers/{commandId}", commandId)
                    .with(user(owner.getUsername()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated())
            .andExpect(content().string(firstResponse));

        assertEquals(
            transfersBefore + 1,
            transferRepository.count()
        );

        var saved = transferRepository.findBySyncId(syncId)
            .orElseThrow();

        assertEquals(clientDate, saved.getDateTime());

        assertEquals(
            0,
            new BigDecimal("25.00").compareTo(
                accountRepository.findById(source.getAccount_id())
                    .orElseThrow()
                    .getAmount()
            )
        );

        assertEquals(
            0,
            new BigDecimal("175.00").compareTo(
                accountRepository.findById(
                    destination.getAccount_id()
                ).orElseThrow().getAmount()
            )
        );
    }
}