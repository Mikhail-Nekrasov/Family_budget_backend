package fi.haagahelia.accounting.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.api.dto.CurrencyAmount;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;

public interface AccountRepository extends JpaRepository<Account, Long> {
    
    List<Account> findByName(String name);
    List<Account> findByUser(User user);

    @Query("""
        SELECT new fi.haagahelia.accounting.api.dto.CurrencyAmount(
            a.currencyCode,
            SUM(a.amount)
        )
        FROM Account a
        WHERE a.user = :user
        GROUP BY a.currencyCode
        ORDER BY a.currencyCode
        """)
    List<CurrencyAmount> sumBalancesByCurrency(
        @Param("user") User user
    );

    @Query("""
        SELECT COUNT(a) > 0
        FROM Account a
        WHERE a.syncId = :syncId
        """)
    boolean existsBySyncId(@Param("syncId") UUID syncId);

    @Query("""
        SELECT a
        FROM Account a
        WHERE a.syncId = :syncId AND a.user = :user
        """)
    Optional<Account> findOwnedBySyncId(
        @Param("syncId") UUID syncId,
        @Param("user") User user
    );
    Optional<Account> findBySyncId(UUID syncId);
}
