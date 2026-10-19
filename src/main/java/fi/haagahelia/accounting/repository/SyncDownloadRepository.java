package fi.haagahelia.accounting.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import fi.haagahelia.accounting.api.dto.SyncDownloadItem;

@Repository
public class SyncDownloadRepository {
    public record Download(
        UUID id, String snapshot, String mode, long itemCount,
        Instant pagesExpireAt, Instant cursorExpiresAt
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public SyncDownloadRepository(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public Optional<Download> find(UUID id, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT id, visibility_snapshot, mode, item_count,
                   pages_expire_at, cursor_expires_at
            FROM sync_download WHERE id = ? AND user_id = ?
            """,
            (rs, row) -> new Download(
                rs.getObject("id", UUID.class), rs.getString("visibility_snapshot"),
                rs.getString("mode"), rs.getLong("item_count"),
                rs.getTimestamp("pages_expire_at").toInstant(),
                rs.getTimestamp("cursor_expires_at").toInstant()
            ), id, userId
        ).stream().findFirst();
    }

    public void create(UUID id, Long userId, String mode) {
        jdbcTemplate.update(
            """
            INSERT INTO sync_download(id, user_id, visibility_snapshot, mode)
            VALUES (?, ?, pg_current_snapshot()::text, ?)
            """, id, userId, mode
        );
    }

    public void materializeFull(UUID id, Long userId) {
        jdbcTemplate.update(
            """
            INSERT INTO sync_download_item
                (download_id, position, entity_type, sync_id, action, payload)
            SELECT ?, row_number() OVER (ORDER BY entity_type, sync_id),
                   entity_type, sync_id, 'UPSERT', payload
            FROM sync_personal_entity WHERE user_id = ?
            """, id, userId
        );
        updateCount(id);
    }

    public void materializeDelta(UUID id, Long userId, String previousSnapshot) {
        jdbcTemplate.update(
            """
            INSERT INTO sync_download_item
                (download_id, position, entity_type, sync_id, action, payload)
            SELECT ?, row_number() OVER (ORDER BY changed.entity_type, changed.sync_id),
                   changed.entity_type, changed.sync_id,
                   CASE WHEN current_entity.sync_id IS NULL THEN 'DELETE' ELSE 'UPSERT' END,
                   current_entity.payload
            FROM (
                SELECT DISTINCT entity_type, sync_id FROM sync_change
                WHERE user_id = ?
                AND NOT pg_visible_in_snapshot(transaction_id, CAST(? AS pg_snapshot))
            ) changed
            LEFT JOIN sync_personal_entity current_entity
                ON current_entity.user_id = ? AND current_entity.entity_type = changed.entity_type
                AND current_entity.sync_id = changed.sync_id
            """, id, userId, previousSnapshot, userId
        );
        updateCount(id);
    }

    private void updateCount(UUID id) {
        jdbcTemplate.update(
            """
            UPDATE sync_download SET item_count =
                (SELECT COUNT(*) FROM sync_download_item WHERE download_id = ?)
            WHERE id = ?
            """, id, id
        );
    }

    public List<SyncDownloadItem> page(UUID id, long after, int limit) {
        return jdbcTemplate.query(
            """
            SELECT position, entity_type, sync_id, action, payload::text AS payload
            FROM sync_download_item WHERE download_id = ? AND position > ?
            ORDER BY position LIMIT ?
            """,
            (rs, row) -> {
                String payload = rs.getString("payload");
                return new SyncDownloadItem(
                    rs.getLong("position"), rs.getString("entity_type"),
                    rs.getObject("sync_id", UUID.class), rs.getString("action"),
                    jsonMapper.readTree(payload == null ? "null" : payload)
                );
            }, id, after, limit
        );
    }

    public void complete(UUID id, Long userId) {
        jdbcTemplate.update(
            "UPDATE sync_download SET pages_expire_at = clock_timestamp() WHERE id = ? AND user_id = ?",
            id, userId
        );
        jdbcTemplate.update("DELETE FROM sync_download_item WHERE download_id = ?", id);
    }

    public void cleanup() {
        jdbcTemplate.update("""
            DELETE FROM sync_download_item WHERE download_id IN (
                SELECT id FROM sync_download WHERE pages_expire_at < clock_timestamp()
            )
            """);
        jdbcTemplate.update("DELETE FROM sync_download WHERE cursor_expires_at < clock_timestamp()");
        // Prune only changes already visible to EVERY retained cursor. An older,
        // still uncommitted transaction must remain available after it commits.
        jdbcTemplate.update("""
            DELETE FROM sync_change change
            WHERE occurred_at < clock_timestamp() - INTERVAL '30 days'
            AND NOT EXISTS (
                SELECT 1 FROM sync_download download
                WHERE download.user_id = change.user_id
                AND NOT pg_visible_in_snapshot(
                    change.transaction_id, CAST(download.visibility_snapshot AS pg_snapshot)
                )
            )
            """);
    }
}
