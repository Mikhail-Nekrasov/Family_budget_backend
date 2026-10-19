package fi.haagahelia.accounting.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.Category;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByName(String name);
    List<Category> findByType(EntryType type);
    List<Category> findByUser(User user);
    List<Category> findByTypeAndUser(EntryType type, User user);

    @Query("""
        SELECT COUNT(c) > 0
        FROM Category c
        WHERE c.syncId = :syncId
        """)
    boolean existsBySyncId(@Param("syncId") UUID syncId);

    @Query("""
        SELECT c
        FROM Category c
        WHERE c.syncId = :syncId AND c.user = :user
        """)
    Optional<Category> findOwnedBySyncId(
        @Param("syncId") UUID syncId,
        @Param("user") User user
    );
}
