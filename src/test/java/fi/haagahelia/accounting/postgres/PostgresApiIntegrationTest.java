package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.Set;

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

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountService accountService;

    @Test
    void appliesInitialMigration() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM flyway_schema_history
            WHERE version = '1' AND success = TRUE
            """,
            Integer.class
        );

        assertEquals(Integer.valueOf(1), successfulMigrations);
    }

    @Test
    void createsAccountThroughApiOnPostgres() throws Exception {
        User owner = new User();
        owner.setUsername("postgres_api_owner");
        owner.setPassword("unused-in-this-test");
        owner.setRoles(Set.of());

        owner = userRepository.saveAndFlush(owner);

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Postgres account",
                      "amount": 100.00,
                      "description": "Created in PostgreSQL"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Postgres account"))
            .andExpect(jsonPath("$.amount").value(100.00));

        accountRepository.flush();

        var accounts = accountRepository.findByUser(owner);

        assertEquals(1, accounts.size());
        assertEquals(owner.getId(), accounts.get(0).getUser().getId());
        assertEquals(
            0,
            new BigDecimal("100.00").compareTo(accounts.get(0).getAmount())
        );
    }

    @Test
    void transfersMoneyThroughApiOnPostgres() throws Exception {
        User owner = new User();
        owner.setUsername("postgres_transfer_owner");
        owner.setPassword("unused-in-this-test");
        owner.setRoles(Set.of());

        owner = userRepository.saveAndFlush(owner);

        Account source = accountRepository.saveAndFlush(
            new Account(
                "Source",
                new BigDecimal("100.00"),
                "",
                owner
            )
        );

        Account destination = accountRepository.saveAndFlush(
            new Account(
                "Destination",
                new BigDecimal("50.00"),
                "",
                owner
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
                    "description": "Postgres transfer"
                    }
                    """.formatted(
                        source.getAccount_id(),
                        destination.getAccount_id()
                    )))
            .andExpect(status().isCreated());

        accountRepository.flush();

        BigDecimal sourceBalance = jdbcTemplate.queryForObject(
            "SELECT amount FROM account WHERE account_id = ?",
            BigDecimal.class,
            source.getAccount_id()
        );

        BigDecimal destinationBalance = jdbcTemplate.queryForObject(
            "SELECT amount FROM account WHERE account_id = ?",
            BigDecimal.class,
            destination.getAccount_id()
        );

        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(sourceBalance)
        );
        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(destinationBalance)
        );
        assertEquals(transfersBefore + 1, transferRepository.count());

        Integer savedTransfers = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM transfer
            WHERE from_account_id = ?
            AND to_account_id = ?
            AND amount = ?
            AND description = ?
            """,
            Integer.class,
            source.getAccount_id(),
            destination.getAccount_id(),
            new BigDecimal("25.00"),
            "Postgres transfer"
        );

        assertEquals(Integer.valueOf(1), savedTransfers);
    }

    @Test
    void calculatesAccountExpensesInDatabase() {
        Long ownerId = jdbcTemplate.queryForObject(
            """
            INSERT INTO users (username, password)
            VALUES ('postgres_expense_totals_user', 'unused')
            RETURNING id
            """,
            Long.class
        );

        Long accountId = jdbcTemplate.queryForObject(
            """
            INSERT INTO account (name, amount, user_id)
            VALUES ('Totals account', 100, ?)
            RETURNING account_id
            """,
            Long.class,
            ownerId
        );

        Long categoryId = jdbcTemplate.queryForObject(
            """
            INSERT INTO category (name, type, user_id)
            VALUES ('Totals category', 'EXPENSE', ?)
            RETURNING category_id
            """,
            Long.class,
            ownerId
        );

        jdbcTemplate.update(
            """
            INSERT INTO entry (
                title, type, amount, date_time,
                category_id, account_id, user_id
            )
            VALUES
                ('First expense', 'EXPENSE', 10, CURRENT_TIMESTAMP, ?, ?, ?),
                ('Second expense', 'EXPENSE', 25, CURRENT_TIMESTAMP, ?, ?, ?)
            """,
            categoryId, accountId, ownerId,
            categoryId, accountId, ownerId
        );

        User owner = userRepository
            .findByUsername("postgres_expense_totals_user");

        var totals = accountService.getAccountSpent(owner);

        assertEquals(1, totals.size());
        assertEquals(
            0,
            new BigDecimal("35.00").compareTo(totals.get(accountId))
        );
    }

    @Test
    void calculatesTotalBalanceForCurrentUserOnly() {
        User owner = new User();
        owner.setUsername("postgres_balance_owner");
        owner.setPassword("unused");
        owner.setRoles(Set.of());
        owner = userRepository.saveAndFlush(owner);

        User other = new User();
        other.setUsername("postgres_balance_other");
        other.setPassword("unused");
        other.setRoles(Set.of());
        other = userRepository.saveAndFlush(other);

        accountRepository.saveAndFlush(
            new Account("Bank", new BigDecimal("100.00"), "", owner)
        );
        accountRepository.saveAndFlush(
            new Account("Debt", new BigDecimal("-25.00"), "", owner)
        );
        accountRepository.saveAndFlush(
            new Account("Foreign", new BigDecimal("999.00"), "", other)
        );

        assertEquals(
            0,
            new BigDecimal("75.00").compareTo(
                accountService.getTotalBalance(owner)
            )
        );
    }

    @Test
    void returnsZeroTotalBalanceWithoutAccounts() {
        User owner = new User();
        owner.setUsername("postgres_empty_balance_owner");
        owner.setPassword("unused");
        owner.setRoles(Set.of());
        owner = userRepository.saveAndFlush(owner);

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(accountService.getTotalBalance(owner))
        );
    }

    @Test
    void rejectsAccountAmountWithExcessDecimalPlaces() throws Exception {
        User owner = new User();
        owner.setUsername("postgres_invalid_precision_owner");
        owner.setPassword("unused");
        owner.setRoles(Set.of());
        owner = userRepository.saveAndFlush(owner);

        mockMvc.perform(post("/api/accounts")
                .with(user(owner.getUsername()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                    "name": "Invalid amount",
                    "amount": 100.001
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "Amount must have at most two decimal places"
            ));

        assertEquals(0, accountRepository.findByUser(owner).size());
    }
}