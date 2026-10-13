package fi.haagahelia.accounting.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.GroupAccountAccess;
import fi.haagahelia.accounting.model.User;

public interface GroupAccountAccessRepository
        extends JpaRepository<GroupAccountAccess, Long> {

    @Query("""
        SELECT access
        FROM GroupAccountAccess access
        WHERE access.group.id = :groupId
        AND access.account.account_id = :accountId
        """)
    Optional<GroupAccountAccess> findAccess(
        @Param("groupId") Long groupId,
        @Param("accountId") Long accountId
    );

    @Query("""
        SELECT access
        FROM GroupAccountAccess access
        JOIN FETCH access.account account
        WHERE access.group.id = :groupId
        AND EXISTS (
            SELECT membership.id
            FROM GroupMembership membership
            WHERE membership.group.id = :groupId
                AND membership.user = account.user
        )
        ORDER BY account.name ASC, account.account_id ASC
        """)
    List<GroupAccountAccess> findSharedAccounts(
        @Param("groupId") Long groupId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
        DELETE FROM GroupAccountAccess access
        WHERE access.group.id = :groupId
        AND access.account.user = :user
        """)
    int deleteSharedAccountsOfUser(
        @Param("groupId") Long groupId,
        @Param("user") User user
    );

    @Query("""
        SELECT COUNT(access)
        FROM GroupAccountAccess access
        WHERE access.account.account_id = :accountId
        AND access.permission IN :permissions
        AND EXISTS (
            SELECT membership.id
            FROM GroupMembership membership
            WHERE membership.group = access.group
                AND membership.user = :user
        )
        AND EXISTS (
            SELECT ownerMembership.id
            FROM GroupMembership ownerMembership
            WHERE ownerMembership.group = access.group
                AND ownerMembership.user = access.account.user
        )
        """)
    long countAvailableAccess(
        @Param("accountId") Long accountId,
        @Param("user") User user,
        @Param("permissions") Collection<AccountPermission> permissions
    );
}