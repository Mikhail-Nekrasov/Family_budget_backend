package fi.haagahelia.accounting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.User;

public interface BudgetGroupRepository extends JpaRepository<BudgetGroup, Long> {

    @Query("""
        SELECT g
        FROM BudgetGroup g
        WHERE EXISTS (
                SELECT m.id
                FROM GroupMembership m
                WHERE m.group = g AND m.user = :user
        )
        ORDER BY g.createdAt DESC, g.id DESC
        """)
    List<BudgetGroup> findGroupsByMember(@Param("user") User user);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM BudgetGroup g WHERE g.id = :id")
    int deleteGroupById(@Param("id") Long id);
}