package fi.haagahelia.accounting.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SyncCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public SyncCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record CompletedCommand(
        String operation,
        String requestHash,
        int responseStatus,
        String responseBody
    ) {
    }

    public Optional<CompletedCommand> findCompleted(
            Long userId, UUID commandId) {

        return jdbcTemplate.query(
            """
            SELECT operation, request_hash,
                   response_status, response_body
            FROM sync_command
            WHERE user_id = ? AND command_id = ?
            """,
            (resultSet, rowNumber) -> new CompletedCommand(
                Objects.requireNonNull(
                    resultSet.getString("operation")
                ),
                Objects.requireNonNull(
                    resultSet.getString("request_hash")
                ),
                resultSet.getInt("response_status"),
                Objects.requireNonNull(
                    resultSet.getString("response_body")
                )
            ),
            userId,
            commandId
        ).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveCompleted(
            Long userId,
            UUID commandId,
            CompletedCommand command) {

        jdbcTemplate.update(
            """
            INSERT INTO sync_command (
                user_id, command_id, operation, request_hash,
                response_status, response_body
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            userId,
            commandId,
            command.operation(),
            command.requestHash(),
            command.responseStatus(),
            command.responseBody()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUser(Long userId) {
        var ids = jdbcTemplate.query(
            "SELECT id FROM users WHERE id = ? FOR UPDATE",
            (resultSet, rowNumber) -> resultSet.getLong("id"),
            userId
        );

        if (ids.isEmpty()) {
            throw new IllegalStateException("User not found");
        }
    }
}