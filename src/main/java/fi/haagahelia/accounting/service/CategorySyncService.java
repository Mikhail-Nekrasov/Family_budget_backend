package fi.haagahelia.accounting.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CategoryResponse;
import fi.haagahelia.accounting.api.dto.CreateCategoryRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteCategoryRequest;
import fi.haagahelia.accounting.api.dto.SyncDeleteResponse;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;

@Service
public class CategorySyncService {

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final SyncCommandService syncCommandService;

    public CategorySyncService(
            CategoryService categoryService,
            SyncCommandService syncCommandService,
            CategoryRepository categoryRepository) {

        this.categoryRepository = categoryRepository;

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
    @Transactional
    public CompletedCommand deleteCategory(
            UUID commandId, SyncDeleteCategoryRequest request, User currentUser) {
        if (request.syncId() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Category sync ID is required"
            );
        }
        Long expectedVersion = request.expectedVersion();
        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "A non-negative expected version is required"
            );
        }
        return syncCommandService.execute(
            currentUser.getId(), commandId, "DELETE_CATEGORY", request,
            HttpStatus.OK.value(), () -> {
                Category category = categoryRepository.findOwnedBySyncId(
                    request.syncId(), currentUser
                ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Category is unavailable for synchronization"
                ));
                if (!expectedVersion.equals(category.getVersion())) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Category version conflict. Synchronize before retrying."
                    );
                }
                categoryService.deleteCategory(category.getCategory_id(), currentUser);
                categoryRepository.flush();
                return new SyncDeleteResponse(request.syncId(), true);
            }
        );
    }
}
