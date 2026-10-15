package fi.haagahelia.accounting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByName(String name);
    List<Category> findByType(EntryType type);
    List<Category> findByUser(User user);
    List<Category> findByTypeAndUser(EntryType type, User user);

}
