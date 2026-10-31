package fi.haagahelia.accounting.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.Account;
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
        SELECT e.account.account_id AS accountId, SUM(e.amount) AS total
        FROM Entry e
        WHERE e.user = :user AND e.type = :type
        GROUP BY e.account.account_id
        """)
    List<AccountExpenseTotal> sumAmountsByAccount(
        @Param("user") User user,
        @Param("type") EntryType type
    );

    Page<Entry> findByAccount(Account account, Pageable pageable);

    Page<Entry> findByAccountAndType(
        Account account,
        EntryType type,
        Pageable pageable
    );

    @Query("""
        SELECT COUNT(e) > 0
        FROM Entry e
        WHERE e.syncId = :syncId
        """)
    boolean existsBySyncId(@Param("syncId") UUID syncId);

    @Query("""
        SELECT e
        FROM Entry e
        WHERE e.syncId = :syncId AND e.user = :user
        """)
    Optional<Entry> findOwnedBySyncId(
        @Param("syncId") UUID syncId,
        @Param("user") User user
    );
}
