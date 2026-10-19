package fi.haagahelia.accounting.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;


public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Query("""
        SELECT t FROM Transfer t
        WHERE t.fromAccount.user = :user
        OR t.toAccount.user = :user
        ORDER BY t.dateTime DESC, t.id DESC
        """)
    List<Transfer> findVisibleToUser(@Param("user") User user);

    List<Transfer> findByDateTime(LocalDateTime dateTime);

    @Query("""
        SELECT t FROM Transfer t
        WHERE t.fromAccount.user = :user OR t.toAccount.user = :user
        ORDER BY t.dateTime DESC, t.id DESC
        """)
    Page<Transfer> findVisiblePageByUser(
        @Param("user") User user,
        Pageable pageable
    );

    @Query("""
        SELECT COUNT(t) > 0
        FROM Transfer t
        WHERE t.syncId = :syncId
        """)
    boolean existsBySyncId(@Param("syncId") UUID syncId);

    @Query("""
        SELECT t
        FROM Transfer t
        WHERE t.syncId = :syncId
        """)
    Optional<Transfer> findBySyncId(@Param("syncId") UUID syncId);
}
