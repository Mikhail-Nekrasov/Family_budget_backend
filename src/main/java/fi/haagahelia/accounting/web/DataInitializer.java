package fi.haagahelia.accounting.web;

import java.math.BigDecimal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.Role;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.CategoryRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.RoleRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@Component
@Profile({"dev", "test"})
public class DataInitializer implements CommandLineRunner{

    private final EntryRepository entryRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DataInitializer(EntryRepository entryRepository,
                           CategoryRepository categoryRepository,
                           AccountRepository accountRepository,
                           UserRepository userRepository,
                           RoleRepository roleRepository,
                           BCryptPasswordEncoder passwordEncoder) {
        this.entryRepository = entryRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {

        // 🚫 Skip initialization if data already exists
        if (userRepository.findByUsername("admin") != null) {
            return;
        }

        // User
        Role role = roleRepository.findByName("ROLE_USER");
        if (role == null) {
            role = new Role();
            role.setName("ROLE_USER");
            roleRepository.save(role);
        }

        User admin = userRepository.findByUsername("admin");
        if (admin == null) {
            admin = new User("admin", passwordEncoder.encode("admin"), role);
            userRepository.save(admin);
        }

        // Expense categories
        Category groceriesCategory = new Category("Groceries", EntryType.EXPENSE, admin);
        Category rentCategory = new Category("Rent", EntryType.EXPENSE, admin);
        Category transportCategory = new Category("Transport", EntryType.EXPENSE, admin);
        Category clothesCategory = new Category("Clothes", EntryType.EXPENSE, admin);
        Category entertainmentCategory = new Category("Entertainment", EntryType.EXPENSE, admin);
        Category healthCategory = new Category("Health", EntryType.EXPENSE, admin);
        Category taxCategory = new Category("Tax", EntryType.EXPENSE, admin);
        Category homeCategory = new Category("Home", EntryType.EXPENSE, admin);
        Category mobileComAndInternetCategory = new Category("Mobile Communication and Internet", EntryType.EXPENSE, admin);
        Category bankServicesCategory = new Category("Bank Services", EntryType.EXPENSE, admin);
        Category subscriptionsAndServicesCategory = new Category("Subscriptions and Services", EntryType.EXPENSE, admin);
        Category giftsCategory = new Category("Gifts", EntryType.EXPENSE, admin);
        Category educationCategory = new Category("Education", EntryType.EXPENSE, admin);
        Category otherExpencesCategory = new Category("Other Expenses", EntryType.EXPENSE, admin);

        categoryRepository.save(groceriesCategory);
        categoryRepository.save(rentCategory);
        categoryRepository.save(transportCategory);
        categoryRepository.save(clothesCategory);
        categoryRepository.save(entertainmentCategory);
        categoryRepository.save(healthCategory);
        categoryRepository.save(taxCategory);
        categoryRepository.save(homeCategory);
        categoryRepository.save(mobileComAndInternetCategory);
        categoryRepository.save(bankServicesCategory);
        categoryRepository.save(subscriptionsAndServicesCategory);
        categoryRepository.save(giftsCategory);
        categoryRepository.save(educationCategory);
        categoryRepository.save(otherExpencesCategory);

        // Income categories
        Category salaryCategory = new Category("Salary", EntryType.INCOME, admin);
        Category interestCategory = new Category("Interest", EntryType.INCOME, admin);
        Category giftsIncomeCategory = new Category("Gifts Income", EntryType.INCOME, admin);
        Category rentIncomCategory = new Category("Rent Income", EntryType.INCOME, admin);
        Category otherIncomeCategory = new Category("Other Income", EntryType.INCOME, admin);

        categoryRepository.save(salaryCategory);
        categoryRepository.save(interestCategory);
        categoryRepository.save(giftsIncomeCategory);
        categoryRepository.save(rentIncomCategory);
        categoryRepository.save(otherIncomeCategory);



        // Accounts
        Account cashAccount = new Account("Cash", BigDecimal.ZERO, "Physical cash on hand", admin);
        Account bankOPAccount = new Account("Bank OP", BigDecimal.ZERO, "Main bank account at OP", admin);
        Account bankSPankkiAccount = new Account("Bank S-Pankki", BigDecimal.ZERO, "Secondary bank account at S-Pankki", admin);

        accountRepository.save(cashAccount);
        accountRepository.save(bankOPAccount);
        accountRepository.save(bankSPankkiAccount);

        // Entries
        Entry entry1 = new Entry("Grocery Shopping", EntryType.EXPENSE,
                new BigDecimal("75.50"),
                java.time.LocalDateTime.now().minusDays(2),
                "Weekly groceries at the K market",
                groceriesCategory,
                cashAccount,
                admin);

        Entry entry2 = new Entry("Monthly Rent", EntryType.EXPENSE,
                new BigDecimal("950.00"),
                java.time.LocalDateTime.now().minusDays(5),
                "Rent for the apartment",
                rentCategory,
                bankOPAccount,
                admin);

        Entry entry3 = new Entry("Salary", EntryType.INCOME,
                new BigDecimal("2500.00"),
                java.time.LocalDateTime.now().minusDays(10),
                "Monthly salary for October",
                salaryCategory,
                bankOPAccount,
                admin);

        Entry entry4 = new Entry("Publick transport ticket", EntryType.EXPENSE,
                new BigDecimal("64.60"),
                java.time.LocalDateTime.now().minusDays(15),
                "Season ticket, ABC zones, 30 days for a student",
                transportCategory,
                cashAccount,
                admin);

        Entry entry5 = new Entry("Furniture", EntryType.EXPENSE,
                new BigDecimal("470.00"),
                java.time.LocalDateTime.now().minusDays(20),
                "Table, chairs and cutlery for kitchen from IKEA",
                homeCategory,
                bankSPankkiAccount,
                admin);

        Entry entry6 = new Entry("Jacket", EntryType.EXPENSE,
                new BigDecimal("120.00"),
                java.time.LocalDateTime.now().minusDays(3),
                "Winter jacket from outdoor store",
                clothesCategory,
                cashAccount,
                admin);

        Entry entry7 = new Entry("Birthday Gift", EntryType.EXPENSE,
                new BigDecimal("25.00"),
                java.time.LocalDateTime.now().minusDays(15),
                "Earphones as a birthday present",
                giftsCategory,
                bankSPankkiAccount,
                admin);

        Entry[] entries = {entry1, entry2, entry3, entry4, entry5, entry6, entry7};

        for (Entry e : entries) {
            Account acc = e.getAccount();
            BigDecimal currentAmount = acc.getAmount() != null ? acc.getAmount() : BigDecimal.ZERO;

            if (e.getType() == EntryType.INCOME) {
                acc.setAmount(currentAmount.add(e.getAmount()));
            } else if (e.getType() == EntryType.EXPENSE) {
                acc.setAmount(currentAmount.subtract(e.getAmount()));
            }

            accountRepository.save(acc);
            entryRepository.save(e);
        }
    }
}
