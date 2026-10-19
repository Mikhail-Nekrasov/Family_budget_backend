package fi.haagahelia.accounting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransferApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void returnsOwnTransfersWithoutForeignOnes() throws Exception {
        User owner = createUser("api_transfer_owner");
        User other = createUser("api_transfer_other");

        Account source = createAccount("Bank", owner);
        Account destination = createAccount("Cash", owner);

        Transfer ownTransfer = transferRepository.save(
            new Transfer(
                source, destination, new BigDecimal("25.00"), "Own transfer"
            )
        );

        Account foreignSource = createAccount("Other bank", other);
        Account foreignDestination = createAccount("Other cash", other);

        transferRepository.save(
            new Transfer(
                foreignSource,
                foreignDestination,
                new BigDecimal("99.00"),
                "Foreign transfer"
            )
        );

        mockMvc.perform(get("/api/transfers")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id")
                .value(ownTransfer.getId().intValue()))
            .andExpect(jsonPath("$.content[0].syncId")
                .value(ownTransfer.getSyncId().toString()))
            .andExpect(jsonPath("$.content[0].fromAccountName").value("Bank"))
            .andExpect(jsonPath("$.content[0].toAccountName").value("Cash"))
            .andExpect(jsonPath("$.content[0].amount").value(25.00))
            .andExpect(jsonPath("$.content[0].description").value("Own transfer"))
            .andExpect(jsonPath("$.content[0].dateTime").isString())
            .andExpect(jsonPath("$.content[0].fromAccount").doesNotExist())
            .andExpect(jsonPath("$.content[0].toAccount").doesNotExist());
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused-in-this-test");

        return userRepository.save(user);
    }

    private Account createAccount(String name, User owner) {
        return accountRepository.save(
            new Account(name, new BigDecimal("100.00"), "", owner)
        );
    }

    @Test
    void createsTransferAndUpdatesBothBalances() throws Exception {
        User owner = createUser("api_create_transfer_owner");

        Account source = createAccount("Bank", owner);
        Account destination = createAccount("Cash", owner);

        long transfersBefore = transferRepository.count();

        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "fromAccountId": %d,
                    "toAccountId": %d,
                    "amount": 25.00,
                    "description": "Cash withdrawal"
                    }
                    """.formatted(
                        source.getAccount_id(),
                        destination.getAccount_id()
                    )))
            .andExpect(status().isCreated())
            .andExpect(content().string(""));

        Account updatedSource = accountRepository
            .findById(source.getAccount_id())
            .orElseThrow();

        Account updatedDestination = accountRepository
            .findById(destination.getAccount_id())
            .orElseThrow();

        // Both accounts initially contain 100.
        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(updatedSource.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("125.00").compareTo(updatedDestination.getAmount())
        );
        assertEquals(transfersBefore + 1, transferRepository.count());
    }

    @Test
    void rejectsTransferWithInsufficientFundsWithoutChanges() throws Exception {
        User owner = createUser("api_insufficient_transfer_owner");

        Account source = createAccount("Bank", owner);
        Account destination = createAccount("Cash", owner);

        long transfersBefore = transferRepository.count();

        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "fromAccountId": %d,
                    "toAccountId": %d,
                    "amount": 101.00,
                    "description": "Must be rejected"
                    }
                    """.formatted(
                        source.getAccount_id(),
                        destination.getAccount_id()
                    )))
            .andExpect(status().isConflict());

        Account unchangedSource = accountRepository
            .findById(source.getAccount_id())
            .orElseThrow();

        Account unchangedDestination = accountRepository
            .findById(destination.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(unchangedSource.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(unchangedDestination.getAmount())
        );
        assertEquals(transfersBefore, transferRepository.count());
    }

    @Test
    void returnsTransfersInSeparatePages() throws Exception {
        User owner = createUser("api_transfer_page_owner");

        Account source = createAccount("Bank", owner);
        Account destination = createAccount("Cash", owner);

        Transfer older = transferRepository.saveAndFlush(
            new Transfer(
                source, destination, new BigDecimal("10"), "Older"
            )
        );

        Transfer newer = transferRepository.saveAndFlush(
            new Transfer(
                source, destination, new BigDecimal("20"), "Newer"
            )
        );

        mockMvc.perform(get("/api/transfers")
                .param("page", "0")
                .param("size", "1")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id")
                .value(newer.getId().intValue()));

        mockMvc.perform(get("/api/transfers")
                .param("page", "1")
                .param("size", "1")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id")
                .value(older.getId().intValue()));
    }

    @Test
    void rejectsRepeatedTransferWithoutMovingMoneyTwice()
            throws Exception {

        User owner = createUser("api_transfer_sync_owner");

        Account source = createAccount("Source", owner);
        Account destination = createAccount("Destination", owner);

        UUID syncId = UUID.randomUUID();
        long transfersBefore = transferRepository.count();

        String requestBody = """
            {
                "fromAccountId": %d,
                "toAccountId": %d,
                "amount": 75.00,
                "description": "Offline transfer",
                "syncId": "%s"
            }
            """.formatted(
                source.getAccount_id(),
                destination.getAccount_id(),
                syncId
            );

        // Both accounts initially contain 100.
        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated());

        transferRepository.flush();

        Account sourceAfterFirst = accountRepository
            .findById(source.getAccount_id())
            .orElseThrow();

        Account destinationAfterFirst = accountRepository
            .findById(destination.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("25.00")
                .compareTo(sourceAfterFirst.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("175.00")
                .compareTo(destinationAfterFirst.getAmount())
        );
        assertEquals(transfersBefore + 1, transferRepository.count());

        // The same UUID must be rejected before another transfer occurs.
        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message")
                .value("Transfer sync ID is already in use"));

        Account sourceAfterRepeat = accountRepository
            .findById(source.getAccount_id())
            .orElseThrow();

        Account destinationAfterRepeat = accountRepository
            .findById(destination.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("25.00")
                .compareTo(sourceAfterRepeat.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("175.00")
                .compareTo(destinationAfterRepeat.getAmount())
        );
        assertEquals(transfersBefore + 1, transferRepository.count());

        mockMvc.perform(get("/api/transfers")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].syncId")
                .value(syncId.toString()));
    }

    @Test
    void rejectsForeignTransferSyncIdWithoutMovingMoney()
            throws Exception {

        User owner = createUser("api_transfer_sync_requester");
        User other = createUser("api_transfer_sync_other");

        Account source = createAccount("Own source", owner);
        Account destination = createAccount("Own destination", owner);

        Account foreignSource = createAccount("Private source", other);
        Account foreignDestination = createAccount(
            "Private destination", other
        );

        Transfer foreignTransfer = transferRepository.saveAndFlush(
            new Transfer(
                foreignSource,
                foreignDestination,
                new BigDecimal("10.00"),
                "Private transfer"
            )
        );

        long transfersBefore = transferRepository.count();

        mockMvc.perform(post("/api/transfers")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "fromAccountId": %d,
                        "toAccountId": %d,
                        "amount": 25.00,
                        "description": "Attempted duplicate",
                        "syncId": "%s"
                    }
                    """.formatted(
                        source.getAccount_id(),
                        destination.getAccount_id(),
                        foreignTransfer.getSyncId()
                    )))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message")
                .value("Transfer sync ID is already in use"))
            .andExpect(jsonPath("$.fromAccountId").doesNotExist())
            .andExpect(jsonPath("$.toAccountId").doesNotExist())
            .andExpect(jsonPath("$.description").doesNotExist());

        assertEquals(transfersBefore, transferRepository.count());

        Account unchangedSource = accountRepository
            .findById(source.getAccount_id())
            .orElseThrow();

        Account unchangedDestination = accountRepository
            .findById(destination.getAccount_id())
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("100.00")
                .compareTo(unchangedSource.getAmount())
        );
        assertEquals(
            0,
            new BigDecimal("100.00")
                .compareTo(unchangedDestination.getAmount())
        );

        mockMvc.perform(get("/api/transfers")
                .with(user(owner.getUsername()))
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.content.length()").value(0));
    }
}