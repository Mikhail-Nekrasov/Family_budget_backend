package fi.haagahelia.accounting.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SyncTombstoneRepository {

    private final JdbcTemplate jdbcTemplate;

    public SyncTombstoneRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEntryDeletion(Long userId, UUID syncId) {
        jdbcTemplate.update(
            """
            INSERT INTO sync_tombstone (
                user_id, entity_type, sync_id
            )
            VALUES (?, 'ENTRY', ?)
            """,
            userId,
            syncId
        );
    }

    public boolean entryWasDeleted(Long userId, UUID syncId) {
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM sync_tombstone
            WHERE user_id = ?
            AND entity_type = 'ENTRY'
            AND sync_id = ?
            """,
            Long.class,
            userId,
            syncId
        );

        return count != null && count > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAccountDeletion(Long userId, UUID syncId) {
        jdbcTemplate.update(
            """
            INSERT INTO sync_tombstone (
                user_id, entity_type, sync_id
            )
            VALUES (?, 'ACCOUNT', ?)
            """,
            userId,
            syncId
        );
    }

    public boolean accountWasDeleted(Long userId, UUID syncId) {
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM sync_tombstone
            WHERE user_id = ?
            AND entity_type = 'ACCOUNT'
            AND sync_id = ?
            """,
            Long.class,
            userId,
            syncId
        );

        return count != null && count > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCategoryDeletion(Long userId, UUID syncId) {
        jdbcTemplate.update(
            """
            INSERT INTO sync_tombstone (
                user_id, entity_type, sync_id
            )
            VALUES (?, 'CATEGORY', ?)
            """,
            userId,
            syncId
        );
    }

    public boolean categoryWasDeleted(Long userId, UUID syncId) {
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM sync_tombstone
            WHERE user_id = ?
            AND entity_type = 'CATEGORY'
            AND sync_id = ?
            """,
            Long.class,
            userId,
            syncId
        );

        return count != null && count > 0;
    }
}