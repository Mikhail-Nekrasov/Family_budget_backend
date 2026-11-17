package fi.haagahelia.accounting.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.repository.SyncCommandRepository;
import fi.haagahelia.accounting.service.DeletedSyncDependencyException.Dependency;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SyncDependencyRecoveryService {
    private static final long MAX_SAFE_VERSION = 9_007_199_254_740_989L;
    private final JdbcTemplate jdbc;
    private final SyncCommandRepository commands;
    private final JsonMapper jsonMapper;
    @PersistenceContext
    private EntityManager entityManager;

    public SyncDependencyRecoveryService(JdbcTemplate jdbc, SyncCommandRepository commands, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.jsonMapper = jsonMapper;
    }

    private record Deletion(UUID token, String snapshot, boolean restored) {}
    public record RestoreRequest(String entityType, UUID syncId, UUID restoreToken,
                                 String categoryName, EntryType categoryType, Long categoryVersion) {}
    public record RestoredDependency(UUID syncId, long id, long version) {}

    public ResponseStatusException unavailable(long ownerId, String type, UUID syncId, String fallback) {
        return deletion(ownerId, type, syncId, false)
            .filter(row -> !row.restored() && (row.snapshot() != null || type.equals("CATEGORY")))
            .<ResponseStatusException>map(row -> new DeletedSyncDependencyException(
                new Dependency(type, syncId, row.token(), row.snapshot() == null)))
            .orElseGet(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, fallback));
    }

    @Transactional
    public RestoredDependency restore(long ownerId, RestoreRequest request) {
        boolean account = "ACCOUNT".equals(request.entityType());
        if ((!account && !"CATEGORY".equals(request.entityType())) || request.syncId() == null
                || request.restoreToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dependency restore request");
        }
        commands.lockUser(ownerId);
        Deletion row = deletion(ownerId, request.entityType(), request.syncId(), true)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deleted dependency not found"));
        if (!row.token().equals(request.restoreToken())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dependency deletion has changed");
        }

        String table = account ? "account" : "category";
        String id = account ? "account_id" : "category_id";
        if (!row.restored()) {
            if (account) {
                if (row.snapshot() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Deleted account has no saved balance");
                }
                jdbc.update("""
                    INSERT INTO account (account_id, name, amount, description, version, user_id,
                                         sync_id, currency_code, icon_key, archived_at)
                    SELECT account_id, name, amount, description, version + 1, user_id,
                           sync_id, currency_code, icon_key, archived_at
                    FROM jsonb_populate_record(NULL::account, CAST(? AS jsonb))
                    """, row.snapshot());
            } else if (row.snapshot() != null) {
                jdbc.update("""
                    INSERT INTO category (category_id, name, type, user_id, sync_id, version, default_key)
                    SELECT category_id, name, type, user_id, sync_id, version + 1, default_key
                    FROM jsonb_populate_record(NULL::category, CAST(? AS jsonb))
                    """, row.snapshot());
            } else {
                restoreLegacyCategory(ownerId, request);
            }
            jdbc.update("""
                UPDATE sync_tombstone SET restored = TRUE
                WHERE user_id = ? AND entity_type = ? AND sync_id = ?
                """, ownerId, request.entityType(), request.syncId());
        }

        return jdbc.query(
            "SELECT " + id + ", version FROM " + table + " WHERE user_id = ? AND sync_id = ?",
            (rs, index) -> new RestoredDependency(request.syncId(), rs.getLong(1), rs.getLong(2)),
            ownerId, request.syncId()
        ).stream().findFirst().orElseThrow(
            () -> new ResponseStatusException(HttpStatus.CONFLICT, "Restored dependency is no longer available"));
    }

    private void restoreLegacyCategory(long ownerId, RestoreRequest request) {
        String name = request.categoryName() == null ? "" : request.categoryName().trim();
        Long version = request.categoryVersion();
        if (name.isEmpty() || name.length() > 255 || request.categoryType() == null
                || version == null || version < 0 || version > MAX_SAFE_VERSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid local category details");
        }
        jdbc.update("""
            INSERT INTO category (name, type, user_id, sync_id, version)
            VALUES (?, ?, ?, ?, ?)
            """, name, request.categoryType().name(), ownerId, request.syncId(), version + 1);
    }

    RestoredDependency restoreEditedDependency(long ownerId, String type, UUID syncId, long expectedVersion) {
        Deletion row = deletion(ownerId, type, syncId, true)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                type.equals("ACCOUNT") ? "Account is unavailable for synchronization"
                    : "Category is unavailable for synchronization"));
        nextRestoreVersion(row, expectedVersion, null);
        return restore(ownerId, new RestoreRequest(type, syncId, row.token(), null, null, null));
    }

    long nextRestoreVersion(long ownerId, String type, UUID syncId, long expectedVersion, EntryType entryType) {
        return nextRestoreVersion(deletion(ownerId, type, syncId, true).orElse(null), expectedVersion, entryType);
    }

    private long nextRestoreVersion(Deletion deletion, long expectedVersion, EntryType entryType) {
        if (deletion != null && !deletion.restored() && deletion.snapshot() != null) {
            var snapshot = jsonMapper.readTree(deletion.snapshot());
            var version = snapshot.path("version");
            if (version.isIntegralNumber() && version.longValue() == expectedVersion
                    && expectedVersion < MAX_SAFE_VERSION
                    && (entryType == null || entryType.name().equals(snapshot.path("type").asString()))) {
                return expectedVersion + 1;
            }
        }
        throw new ResponseStatusException(HttpStatus.GONE,
            "The server deleted this operation and its previous version cannot be restored automatically");
    }

    void finishFinancialRestore(long ownerId, String type, UUID syncId, long version, Object operation) {
        String table = "ENTRY".equals(type) ? "entry" : "transfer";
        int updated = jdbc.update("UPDATE " + table + " SET version = ? WHERE sync_id = ?", version, syncId);
        if (updated != 1) throw new IllegalStateException("Restored operation was not found");
        entityManager.refresh(operation);
        jdbc.update("""
            UPDATE sync_tombstone SET restored = TRUE
            WHERE user_id = ? AND entity_type = ? AND sync_id = ?
            """, ownerId, type, syncId);
    }

    private Optional<Deletion> deletion(long ownerId, String type, UUID syncId, boolean lock) {
        return jdbc.query("""
            SELECT restore_token, CAST(restore_snapshot AS TEXT), restored
            FROM sync_tombstone WHERE user_id = ? AND entity_type = ? AND sync_id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            (rs, index) -> new Deletion(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)),
            ownerId, type, syncId
        ).stream().findFirst();
    }
}
