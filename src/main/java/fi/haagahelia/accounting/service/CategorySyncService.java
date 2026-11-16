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
import fi.haagahelia.accounting.api.dto.SyncUpdateCategoryRequest;
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

                return response(saved);
            }
        );
    }

    @Transactional
    public CompletedCommand updateCategory(
            UUID commandId, SyncUpdateCategoryRequest request, User currentUser) {

        requireVersionedTarget(request.syncId(), request.expectedVersion());
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isBlank() || name.length() > 255) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Category name must contain 1 to 255 characters"
            );
        }

        var normalized = new SyncUpdateCategoryRequest(
            request.syncId(), request.expectedVersion(), name
        );
        return syncCommandService.execute(
            currentUser.getId(), commandId, "UPDATE_CATEGORY", normalized,
            HttpStatus.OK.value(), () -> {
                Category category = findOwnedAtVersion(
                    normalized.syncId(), normalized.expectedVersion(), currentUser
                );
                category.setName(normalized.name());
                categoryRepository.flush();
                return response(category);
            }
        );
    }

    @Transactional
    public CompletedCommand deleteCategory(
            UUID commandId, SyncDeleteCategoryRequest request, User currentUser) {

        requireVersionedTarget(request.syncId(), request.expectedVersion());
        return syncCommandService.execute(
            currentUser.getId(), commandId, "DELETE_CATEGORY", request,
            HttpStatus.OK.value(), () -> {
                Category category = findOwnedAtVersion(
                    request.syncId(), request.expectedVersion(), currentUser
                );
                categoryService.deleteCategory(category.getCategory_id(), currentUser);
                categoryRepository.flush();
                return new SyncDeleteResponse(request.syncId(), true);
            }
        );
    }

    private void requireVersionedTarget(UUID syncId, Long expectedVersion) {
        if (syncId == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Category sync ID is required"
            );
        }
        if (expectedVersion == null || expectedVersion < 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "A non-negative expected version is required"
            );
        }
    }

    private Category findOwnedAtVersion(UUID syncId, Long expectedVersion, User currentUser) {
        Category category = categoryRepository.findOwnedBySyncId(syncId, currentUser)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Category is unavailable for synchronization"
            ));
        if (!expectedVersion.equals(category.getVersion())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Category version conflict. Synchronize before retrying."
            );
        }
        return category;
    }

    private CategoryResponse response(Category category) {
        return new CategoryResponse(
            category.getCategory_id(), category.getName(), category.getType(),
            category.isGlobal(), category.getSyncId(), category.getVersion()
        );
    }
}
