package fi.haagahelia.accounting.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.CategoryRepository;

@Service
public class RegistrationCategoryService {

    private record Template(String key, String name, EntryType type) {}

    // Keys match the mobile catalog and remain stable even if labels change.
    private static final List<Template> TEMPLATES = List.of(
        new Template("groceries", "Groceries", EntryType.EXPENSE),
        new Template("transport", "Transport", EntryType.EXPENSE),
        new Template("clothing", "Clothing", EntryType.EXPENSE),
        new Template("rent", "Rent", EntryType.EXPENSE),
        new Template("utilities", "Utilities", EntryType.EXPENSE),
        new Template("restaurants", "Restaurants", EntryType.EXPENSE),
        new Template("gifts", "Gifts", EntryType.EXPENSE),
        new Template("telecom", "Telecom", EntryType.EXPENSE),
        new Template("bankFees", "Bank fees", EntryType.EXPENSE),
        new Template("subscriptions", "Subscriptions", EntryType.EXPENSE),
        new Template("household", "Household", EntryType.EXPENSE),
        new Template("cosmetics", "Cosmetics", EntryType.EXPENSE),
        new Template("health", "Health", EntryType.EXPENSE),
        new Template("sports", "Sports", EntryType.EXPENSE),
        new Template("taxes", "Taxes", EntryType.EXPENSE),
        new Template("insurance", "Insurance", EntryType.EXPENSE),
        new Template("car", "Car", EntryType.EXPENSE),
        new Template("entertainment", "Entertainment", EntryType.EXPENSE),
        new Template("hobbies", "Hobbies", EntryType.EXPENSE),
        new Template("kitchenware", "Kitchenware", EntryType.EXPENSE),
        new Template("furniture", "Furniture", EntryType.EXPENSE),
        new Template("education", "Education", EntryType.EXPENSE),
        new Template("electronics", "Electronics", EntryType.EXPENSE),
        new Template("appliances", "Appliances", EntryType.EXPENSE),
        new Template("family", "Family", EntryType.EXPENSE),
        new Template("videoGames", "Video games", EntryType.EXPENSE),
        new Template("travel", "Travel", EntryType.EXPENSE),
        new Template("pets", "Pets", EntryType.EXPENSE),
        new Template("repairs", "Repairs", EntryType.EXPENSE),
        new Template("charity", "Charity", EntryType.EXPENSE),
        new Template("otherExpense", "Other", EntryType.EXPENSE),
        new Template("salary", "Salary", EntryType.INCOME),
        new Template("interest", "Interest", EntryType.INCOME),
        new Template("rentalIncome", "Rental income", EntryType.INCOME),
        new Template("giftsIncome", "Gifts", EntryType.INCOME),
        new Template("business", "Self-employment", EntryType.INCOME),
        new Template("benefits", "Benefits", EntryType.INCOME),
        new Template("otherIncome", "Other", EntryType.INCOME)
    );

    private final CategoryRepository categories;

    public RegistrationCategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createForNewUser(User user) {
        categories.saveAll(TEMPLATES.stream()
            .map(template -> Category.fromTemplate(
                template.name(), template.type(), user, template.key()
            ))
            .toList());
    }
}
