package fi.haagahelia.accounting.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.User;

import java.time.LocalDateTime;


public interface EntryRepository extends JpaRepository<Entry, Long> {

    //List<Entry> findByType(EntryType type, Pageable pageable);
    Page<Entry> findByType(EntryType type, Pageable pageable);
    Page<Entry> findAll(Pageable pageable);
    List<Entry> findByCategoryName(String categoryName);
    List<Entry> findByDateTime(LocalDateTime dateTime);

    List<Entry> findByType(EntryType type);
    List<Entry> findByUser(User user);
    List<Entry> findAll();
    Page<Entry> findByUser(User currentUser, Pageable pageable);
    Page<Entry> findByUserAndType(User currentUser, EntryType type, Pageable pageable);
    List<Entry> findByUserAndType(User currentUser, EntryType type);

    @Query("""
        SELECT SUM(e.amount)
        FROM Entry e
        WHERE e.user = :user AND e.type = :type
        """)
    BigDecimal sumAmountByUserAndType(
        @Param("user") User user,
        @Param("type") EntryType type
    );

    //List<Entry> findByType(EntryType type, Sort sort);

}
