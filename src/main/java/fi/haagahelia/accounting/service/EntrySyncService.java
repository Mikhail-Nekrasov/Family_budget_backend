package fi.haagahelia.accounting.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateEntryRequest;
import fi.haagahelia.accounting.api.dto.SyncCreateEntryRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteEntryRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteResponse;
import fi.haagahelia.accounting.api.dto.SyncUpdateEntryRequest;
import fi.haagahelia.accounting.api.dto.UpdateEntryRequest;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import fi.haagahelia.accounting.model.User;

@Service
public class EntrySyncService {

    private final EntryService entryService;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final SyncCommandService syncCommandService;
    private final EntryRepository entryRepository;

    public EntrySyncService(
            EntryService entryService,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            SyncCommandService syncCommandService,
            EntryRepository entryRepository) {

        this.entryService = entryService;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.syncCommandService = syncCommandService;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public CompletedCommand createEntry(
            UUID commandId,
            SyncCreateEntryRequest request,
            User currentUser) {

        UUID syncId = request.syncId();
        UUID accountSyncId = request.accountSyncId();
        UUID categorySyncId = request.categorySyncId();

        if (syncId == null
                || accountSyncId == null
                || categorySyncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Entry, account and category sync IDs are required"
            );
        }

        BigDecimal amount = request.amount();

        SyncCreateEntryRequest normalized = new SyncCreateEntryRequest(
            syncId,
            request.title(),
            request.type(),
            amount == null ? null : amount.stripTrailingZeros(),
            request.dateTime(),
            request.description(),
            accountSyncId,
            categorySyncId
        );

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "CREATE_ENTRY",
            normalized,
            HttpStatus.CREATED.value(),
            () -> {
                var account = accountRepository.findOwnedBySyncId(
                    accountSyncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Account is unavailable for synchronization"
                ));

                var category = categoryRepository.findOwnedBySyncId(
                    categorySyncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Category is unavailable for synchronization"
                ));

                CreateEntryRequest entryRequest = new CreateEntryRequest(
                    normalized.title(),
                    normalized.type(),
                    normalized.amount(),
                    normalized.dateTime(),
                    normalized.description(),
                    account.getAccount_id(),
                    category.getCategory_id(),
                    syncId
                );

                return entryService.createEntryFromRequest(
                    entryRequest, currentUser
                );
            }
        );
    }

    @Transactional
    public CompletedCommand updateEntry(
            UUID commandId,
            SyncUpdateEntryRequest request,
            User currentUser) {

        UUID syncId = request.syncId();
        UUID accountSyncId = request.accountSyncId();
        UUID categorySyncId = request.categorySyncId();
        Long expectedVersion = request.expectedVersion();

        if (syncId == null
                || accountSyncId == null
                || categorySyncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Entry, account and category sync IDs are required"
            );
        }

        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A non-negative expected version is required"
            );
        }

        BigDecimal amount = request.amount();

        SyncUpdateEntryRequest normalized = new SyncUpdateEntryRequest(
            syncId,
            expectedVersion,
            request.title(),
            request.type(),
            amount == null ? null : amount.stripTrailingZeros(),
            request.dateTime(),
            request.description(),
            accountSyncId,
            categorySyncId
        );

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "UPDATE_ENTRY",
            normalized,
            HttpStatus.OK.value(),
            () -> {
                var existing = entryRepository.findOwnedBySyncId(
                    syncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Entry is unavailable for synchronization"
                ));

                if (!expectedVersion.equals(existing.getVersion())) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Entry version conflict. Synchronize before retrying."
                    );
                }

                var account = accountRepository.findOwnedBySyncId(
                    accountSyncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Account is unavailable for synchronization"
                ));

                var category = categoryRepository.findOwnedBySyncId(
                    categorySyncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Category is unavailable for synchronization"
                ));

                UpdateEntryRequest updateRequest = new UpdateEntryRequest(
                    normalized.title(),
                    normalized.type(),
                    normalized.amount(),
                    normalized.dateTime(),
                    normalized.description(),
                    account.getAccount_id(),
                    category.getCategory_id()
                );

                return entryService.updateEntryFromRequest(
                    existing.getId(),
                    updateRequest,
                    currentUser
                );
            }
        );
    }

    @Transactional
    public CompletedCommand deleteEntry(
            UUID commandId,
            SyncDeleteEntryRequest request,
            User currentUser) {

        UUID syncId = request.syncId();
        Long expectedVersion = request.expectedVersion();

        if (syncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Entry sync ID is required"
            );
        }

        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A non-negative expected version is required"
            );
        }

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "DELETE_ENTRY",
            request,
            HttpStatus.OK.value(),
            () -> {
                var existing = entryRepository.findOwnedBySyncId(
                    syncId, currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Entry is unavailable for synchronization"
                ));

                if (!expectedVersion.equals(existing.getVersion())) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Entry version conflict. Synchronize before retrying."
                    );
                }

                entryService.deleteEntry(
                    existing.getId(), currentUser
                );

                entryRepository.flush();

                return new SyncDeleteResponse(syncId, true);
            }
        );
    }
}