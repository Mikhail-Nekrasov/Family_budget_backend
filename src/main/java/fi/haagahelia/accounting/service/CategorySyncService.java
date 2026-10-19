package fi.haagahelia.accounting.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CategoryResponse;
import fi.haagahelia.accounting.api.dto.CreateCategoryRequest;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;

@Service
public class CategorySyncService {

    private final CategoryService categoryService;
    private final SyncCommandService syncCommandService;

    public CategorySyncService(
            CategoryService categoryService,
            SyncCommandService syncCommandService) {

        this.categoryService = categoryService;
        this.syncCommandService = syncCommandService;
    }

    @Transactional
    public CompletedCommand createCategory(
            UUID commandId,
            CreateCategoryRequest request,
            User currentUser) {

        UUID syncId = request.syncId();

        if (syncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Sync ID is required for synchronization"
            );
        }

        return syncCommandService.execute(
            currentUser.getId(),
            commandId,
            "CREATE_CATEGORY",
            request,
            HttpStatus.CREATED.value(),
            () -> {
                Category category = new Category(
                    request.name(),
                    request.type(),
                    currentUser
                );

                category.assignSyncId(syncId);

                Category saved = categoryService.createCategory(
                    category, currentUser
                );

                return new CategoryResponse(
                    saved.getCategory_id(),
                    saved.getName(),
                    saved.getType(),
                    saved.isGlobal(),
                    saved.getSyncId(),
                    saved.getVersion()
                );
            }
        );
    }
}