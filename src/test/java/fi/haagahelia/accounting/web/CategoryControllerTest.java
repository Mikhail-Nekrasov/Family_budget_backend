package fi.haagahelia.accounting.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.CategoryService;

import jakarta.servlet.http.HttpServletRequest;

public class CategoryControllerTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Model model;

    @Mock
    private HttpServletRequest request;

    @Mock
    private Authentication auth;

    private CategoryController categoryController;

    private User testUser;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        CategoryService categoryService =
            new CategoryService(categoryRepository);

        categoryController = new CategoryController(
            categoryRepository,
            userRepository,
            categoryService
        );

        // Create a test user
        testUser = new User();
        testUser.setUsername("test_user");

        // Mock authentication
        when(auth.getName()).thenReturn("test_user");

        // Mock userRepository to return testUser
        when(userRepository.findByUsername("test_user")).thenReturn(testUser);

        // Mock HttpServletRequest
        when(request.getRequestURI()).thenReturn("/categories");
        when(request.getQueryString()).thenReturn(null);
    }

    @Test
    void testGetCategoriesReturnsCorrectView() {
        // Prepare some categories
        Category cat1 = new Category("Groceries", EntryType.EXPENSE, testUser);
        Category cat2 = new Category("Salary", EntryType.INCOME, testUser);

        // Mock categoryRepository
        when(categoryRepository.findByUser(testUser)).thenReturn(List.of(cat1, cat2));

        // Call the controller method
        String viewName = categoryController.getCategories(null, request, model, auth);

        // Verify that the correct view is returned
        assertEquals("categories", viewName);

        // Verify that model attributes were set
        verify(model).addAttribute(eq("categories"), any());
        verify(model).addAttribute(eq("type"), isNull());
        verify(model).addAttribute(eq("currentUrl"), eq("/categories"));
    }


    @Test
    public void testShowAddCategoryFormSetsCategoryAndReturnUrl() {
        String returnUrl = "/categories";

        String viewName = categoryController.showAddCategoryForm(returnUrl, EntryType.EXPENSE, model, auth);

        assertEquals("addcategory", viewName);
        verify(model, times(1)).addAttribute(eq("category"), any(Category.class));
        verify(model, times(1)).addAttribute("returnUrl", returnUrl);
    }

    @Test
    public void testSaveCategorySetsUserAndSaves() {
        Category category = new Category();
        category.setName("Test Category");
        category.setType(EntryType.EXPENSE);

        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String returnUrl = "/categories";
        MockHttpSession session = new MockHttpSession();

        String result = categoryController.saveCategory(
            category, returnUrl, session, null, null, auth
        );

        assertEquals("redirect:" + returnUrl, result);
        assertEquals("test_user", category.getUser().getUsername());
        verify(categoryRepository, times(1)).save(category);
    }

    @Test
    public void rejectsDeletingGlobalCategory() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("test_user");

        Category globalCategory = new Category(
            "Groceries",
            EntryType.EXPENSE,
            null
        );

        when(auth.getName()).thenReturn("test_user");
        when(userRepository.findByUsername("test_user"))
            .thenReturn(currentUser);
        when(categoryRepository.findById(10L))
            .thenReturn(Optional.of(globalCategory));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> categoryController.deleteCategory(
                10L, EntryType.EXPENSE, auth
            )
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(categoryRepository, never()).deleteById(anyLong());
    }

    @Test
    public void rejectsDeletingCategoryWithEntries() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setUsername("test_user");

        Category category = new Category(
            "Personal category",
            EntryType.EXPENSE,
            currentUser
        );

        Entry entry = new Entry();
        entry.setCategory(category);
        category.setEntries(List.of(entry));

        when(auth.getName()).thenReturn("test_user");
        when(userRepository.findByUsername("test_user"))
            .thenReturn(currentUser);
        when(categoryRepository.findById(10L))
            .thenReturn(Optional.of(category));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> categoryController.deleteCategory(
                10L, EntryType.EXPENSE, auth
            )
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(categoryRepository, never()).deleteById(anyLong());
    }

    @Test
    public void rejectsCategoryIdOnCreateWithoutWrites() {
        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("other_user");

        Category category = new Category(
            "Other user's category",
            EntryType.EXPENSE,
            otherUser
        );

        ReflectionTestUtils.setField(category, "category_id", 99L);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> categoryController.saveCategory(
                category, "/categories", null, null, null, auth
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertSame(otherUser, category.getUser());

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    public void rejectsMissingOrBlankCategoryName() {
        Category category = new Category();
        category.setType(EntryType.EXPENSE);

        for (String name : new String[] {null, "", "   "}) {
            category.setName(name);

            ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> categoryController.saveCategory(
                    category, "/categories", null, null, null, auth
                )
            );

            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        }

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    public void rejectsMissingCategoryType() {
        Category category = new Category();
        category.setName("Groceries");

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> categoryController.saveCategory(
                category, "/categories", null, null, null, auth
            )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    public void rejectsDeletingForeignCategory() {
        testUser.setId(1L);

        User otherUser = new User();
        otherUser.setId(2L);
        otherUser.setUsername("other_user");

        Category category = new Category(
            "Foreign category",
            EntryType.EXPENSE,
            otherUser
        );

        when(categoryRepository.findById(10L))
            .thenReturn(Optional.of(category));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> categoryController.deleteCategory(
                10L, EntryType.EXPENSE, auth
            )
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(categoryRepository, never()).deleteById(anyLong());
    }

    @Test
    public void deletesOwnCategoryWithoutEntries() {
        testUser.setId(1L);

        Category category = new Category(
            "Personal category",
            EntryType.EXPENSE,
            testUser
        );
        category.setEntries(List.of());

        when(categoryRepository.findById(10L))
            .thenReturn(Optional.of(category));

        String result = categoryController.deleteCategory(
            10L, EntryType.EXPENSE, auth
        );

        assertEquals("redirect:/categories?type=EXPENSE", result);
        verify(categoryRepository).deleteById(10L);
    }
}
