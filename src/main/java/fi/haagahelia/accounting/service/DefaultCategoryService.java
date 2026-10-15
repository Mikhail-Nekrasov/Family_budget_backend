package fi.haagahelia.accounting.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;

@Service
public class DefaultCategoryService {

    private final CategoryRepository categoryRepository;

    public DefaultCategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createForUser(User user) {
        List<Category> categories = new ArrayList<>();

        for (String name : List.of(
                "Groceries",
                "Rent",
                "Transport",
                "Clothes",
                "Entertainment",
                "Health",
                "Tax",
                "Home",
                "Mobile Communication and Internet",
                "Bank Services",
                "Subscriptions and Services",
                "Gifts",
                "Education",
                "Other Expenses")) {

            categories.add(new Category(name, EntryType.EXPENSE, user));
        }

        for (String name : List.of(
                "Salary",
                "Interest",
                "Gifts Income",
                "Rent Income",
                "Other Income")) {

            categories.add(new Category(name, EntryType.INCOME, user));
        }

        categoryRepository.saveAll(categories);
    }
}