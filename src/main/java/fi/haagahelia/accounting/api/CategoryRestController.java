package fi.haagahelia.accounting.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CategoryResponse;
import fi.haagahelia.accounting.api.dto.CreateCategoryRequest;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.CategoryService;

@RestController
@RequestMapping("/api/categories")
public class CategoryRestController {

    private final CategoryService categoryService;
    private final UserRepository userRepository;

    public CategoryRestController(CategoryService categoryService,
                                  UserRepository userRepository) {
        this.categoryService = categoryService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<CategoryResponse> getCategories(
            @RequestParam(value = "type", required = false) EntryType type,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        return categoryService.getCategories(currentUser, type);
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(
            @RequestBody CreateCategoryRequest request,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        Category category = new Category(
            request.name(),
            request.type(),
            currentUser
        );

        Category saved = categoryService.createCategory(category, currentUser);

        CategoryResponse response = new CategoryResponse(
            saved.getCategory_id(),
            saved.getName(),
            saved.getType(),
            saved.isGlobal()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable("id") Long id,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        categoryService.deleteCategory(id, currentUser);

        return ResponseEntity.noContent().build();
    }
}