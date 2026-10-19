package fi.haagahelia.accounting.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.SyncDownloadPage;
import fi.haagahelia.accounting.api.dto.SyncDownloadResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncDownloadRepository;
import fi.haagahelia.accounting.repository.SyncDownloadRepository.Download;

@Service
public class SyncDownloadService {
    public static final int PAGE_SIZE = 200;
    private final SyncDownloadRepository repository;

    public SyncDownloadService(SyncDownloadRepository repository) {
        this.repository = repository;
    }

    // Separate transaction pins one MVCC snapshot for checkpoint + all exported rows.
    // No exported snapshot or database connection stays open while the phone pages.
    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public SyncDownloadResponse start(UUID cursor, User user) {
        Download previous = null;
        if (cursor != null) {
            previous = requireDownload(cursor, user);
            if (!previous.cursorExpiresAt().isAfter(Instant.now())) {
                throw expiredCursor();
            }
        }
        UUID id = UUID.randomUUID();
        String mode = previous == null ? "FULL" : "DELTA";
        repository.create(id, user.getId(), mode);
        if (previous == null) {
            repository.materializeFull(id, user.getId());
        } else {
            repository.materializeDelta(id, user.getId(), previous.snapshot());
        }
        Download result = requireDownload(id, user);
        return new SyncDownloadResponse(
            id, mode, result.itemCount(), PAGE_SIZE,
            result.pagesExpireAt(), result.cursorExpiresAt()
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SyncDownloadPage page(UUID id, long after, User user) {
        Download download = requireDownload(id, user);
        if (!download.pagesExpireAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                HttpStatus.GONE, "Download pages expired. Start a new download."
            );
        }
        if (after < 0 || (after > 0 && after >= download.itemCount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page position");
        }
        var items = repository.page(id, after, PAGE_SIZE);
        long last = items.isEmpty() ? after : items.get(items.size() - 1).position();
        boolean hasMore = last < download.itemCount();
        return new SyncDownloadPage(
            id, download.mode(), items, hasMore ? last : null, hasMore ? null : id
        );
    }

    @Transactional
    public void complete(UUID id, User user) {
        requireDownload(id, user);
        repository.complete(id, user.getId());
    }

    private Download requireDownload(UUID id, User user) {
        return repository.find(id, user.getId()).orElseThrow(() ->
            new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Download or cursor not found. Start a full download if the cursor expired."
            )
        );
    }

    private ResponseStatusException expiredCursor() {
        return new ResponseStatusException(
            HttpStatus.GONE, "Sync cursor expired. Start a full download."
        );
    }
}
