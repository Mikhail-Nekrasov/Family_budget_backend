package fi.haagahelia.accounting.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.User;

public interface AccountRepository extends JpaRepository<Account, Long> {
    
    List<Account> findByName(String name);
    List<Account> findByUser(User user);

    @Query("""
        SELECT SUM(a.amount)
        FROM Account a
        WHERE a.user = :user
        """)
    BigDecimal sumAmountByUser(@Param("user") User user);

}
