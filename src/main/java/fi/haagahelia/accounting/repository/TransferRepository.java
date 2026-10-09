package fi.haagahelia.accounting.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.Transfer;
import fi.haagahelia.accounting.model.User;

import java.util.List;
import java.util.Optional;
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

}
