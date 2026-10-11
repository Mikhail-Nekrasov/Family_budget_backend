package fi.haagahelia.accounting.web;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.CategoryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@Controller
public class CategoryController {

    @Autowired 
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryService categoryService;

    @GetMapping("/categories")
    public String getCategories(@RequestParam(value = "type", required = false) EntryType type, 
                                HttpServletRequest request,
                                Model model,
                                Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }


        List<Category> categories;

        if (type != null) {
            categories = categoryRepository
                .findByTypeAndUserOrTypeAndUserIsNull(
                    type, currentUser, type
                );
        } else {
            categories = categoryRepository
                .findByUserOrUserIsNull(currentUser);
        }

        String currentUrl = request.getRequestURI() + 
            (request.getQueryString() != null ? "?" + request.getQueryString() : "");

        model.addAttribute("categories", categories);
        model.addAttribute("type", type);
        model.addAttribute("currentUrl", currentUrl);

        return "categories";
    }

    @GetMapping("/addcategory")
    public String showAddCategoryForm(@RequestParam(required = false) String returnUrl,
                                      @RequestParam(value = "entryType", required = false) EntryType type,
                                      Model model,
                                      Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        Category category = new Category();
        if (type != null) {
            category.setType(type);
        }
        category.setUser(currentUser);
        model.addAttribute("category", category);
        model.addAttribute("returnUrl", returnUrl);
        return "addcategory";
    }

    @PostMapping("/addcategory")
    public String saveCategory(@ModelAttribute Category category,
                               @RequestParam String returnUrl,
                               HttpSession session,
                               @RequestParam(value = "entryId", required = false) Long entryId,
                               @RequestParam(value = "entryType", required = false) EntryType entryType,
                               Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());
    
        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        categoryService.createCategory(category, currentUser);

        if (entryId != null) {
  
            Object sessionEntry = session.getAttribute("sessionEditEntry_" + entryId);
            if (sessionEntry != null) {
                    session.setAttribute("sessionEditEntry_" + entryId, sessionEntry);
                }
            } else if (entryType != null) {
    
                Object sessionEntry = session.getAttribute("sessionNewEntry_" + entryType.name());
                if (sessionEntry != null) {
                    session.setAttribute("sessionNewEntry_" + entryType.name(), sessionEntry);
                }
            }
            return "redirect:" + returnUrl;
        }

    @PostMapping("/deletecategory/{id}")
    public String deleteCategory(
            @PathVariable("id") Long id,
            @RequestParam(value = "type", required = false) EntryType type,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new IllegalArgumentException("User not found");
        }

        categoryService.deleteCategory(id, currentUser);

        if (type != null) {
            return "redirect:/categories?type=" + type;
        }

        return "redirect:/categories";
    }


    @PostMapping("/saveEntrySession")
    @ResponseBody
        public void saveEntrySession(@ModelAttribute Entry entry, HttpSession session) {
            session.setAttribute("currentEntryData", entry);
    }

}
