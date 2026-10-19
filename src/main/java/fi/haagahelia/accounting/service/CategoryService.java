package fi.haagahelia.accounting.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CategoryResponse;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.SyncTombstoneRepository;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final SyncTombstoneRepository syncTombstoneRepository;

    public CategoryService(
        CategoryRepository categoryRepository,
        SyncTombstoneRepository syncTombstoneRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.syncTombstoneRepository = syncTombstoneRepository;
    }

    @Transactional
    public Category createCategory(Category category, User currentUser) {
        if (category.getCategory_id() != null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "ID must not be supplied when creating a category"
            );
        }

        if (category.getName() == null || category.getName().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Category name is required"
            );
        }

        if (category.getType() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Category type is required"
            );
        }

        if (syncTombstoneRepository.categoryWasDeleted(
                currentUser.getId(), category.getSyncId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Category was deleted and cannot be recreated with the same sync ID"
            );
        }

        if (categoryRepository.existsBySyncId(category.getSyncId())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Category sync ID is already in use"
            );
        }
        category.setUser(currentUser);
        return categoryRepository.save(category);
    }

    @Transactional
    public void deleteCategory(Long id, User currentUser) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Category not found"
            ));

        if (category.isGlobal()
                || !currentUser.getId().equals(category.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "You cannot delete this category"
            );
        }

        if (category.getEntries() != null
                && !category.getEntries().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Move entries to another category before deleting this category"
            );
        }

        categoryRepository.deleteById(id);

        syncTombstoneRepository.recordCategoryDeletion(
            currentUser.getId(), category.getSyncId()
        );
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getCategories(
            User currentUser, EntryType type) {

        List<Category> categories = type == null
            ? categoryRepository.findByUser(currentUser)
            : categoryRepository.findByTypeAndUser(type, currentUser);

        return categories.stream()
            .map(category -> new CategoryResponse(
                category.getCategory_id(),
                category.getName(),
                category.getType(),
                category.isGlobal(),
                category.getSyncId(),
                category.getVersion()
            ))
            .toList();
    }
}