package fi.haagahelia.accounting.api;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.postgres.PostgresTestConfig;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.BudgetGroupRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;
import fi.haagahelia.accounting.repository.GroupInvitationRepository;
import fi.haagahelia.accounting.repository.GroupMembershipRepository;
import fi.haagahelia.accounting.repository.TransferRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountAccessService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
abstract class AbstractGroupApiIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected BudgetGroupRepository groupRepository;

    @Autowired
    protected GroupMembershipRepository membershipRepository;

    @Autowired
    protected GroupInvitationRepository invitationRepository;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected GroupAccountAccessRepository accessRepository;

    @Autowired
    protected CategoryRepository categoryRepository;

    @Autowired
    protected EntryRepository entryRepository;

    @Autowired
    protected AccountAccessService accountAccessService;

    @Autowired
    protected TransferRepository transferRepository;

    protected User owner;

    @BeforeEach
    void setUp() {
        owner = createUser("group_api_owner");
    }

    protected User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused");
        user.setRoles(Set.of());

        return userRepository.saveAndFlush(user);
    }
}