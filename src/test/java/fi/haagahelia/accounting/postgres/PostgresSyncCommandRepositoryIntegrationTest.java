package fi.haagahelia.accounting.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("postgres-test")
@Import(PostgresTestConfig.class)
@Transactional
class PostgresSyncCommandRepositoryIntegrationTest {

    @Autowired
    private SyncCommandRepository syncCommandRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void storesResultAndReturnsItOnlyForItsOwner() {
        User owner = createUser("sync_command_owner");
        User other = createUser("sync_command_other");

        UUID commandId = UUID.randomUUID();

        CompletedCommand expected = new CompletedCommand(
            "CREATE_ACCOUNT",
            "a".repeat(64),
            201,
            "{\"name\":\"Cash\"}"
        );

        assertTrue(
            syncCommandRepository.findCompleted(
                owner.getId(), commandId
            ).isEmpty()
        );

        syncCommandRepository.saveCompleted(
            owner.getId(), commandId, expected
        );

        CompletedCommand actual = syncCommandRepository
            .findCompleted(owner.getId(), commandId)
            .orElseThrow();

        assertEquals(expected, actual);

        assertTrue(
            syncCommandRepository.findCompleted(
                other.getId(), commandId
            ).isEmpty()
        );

        assertTrue(
            syncCommandRepository.findCompleted(
                owner.getId(), UUID.randomUUID()
            ).isEmpty()
        );
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused-in-this-test");

        return userRepository.saveAndFlush(user);
    }
}