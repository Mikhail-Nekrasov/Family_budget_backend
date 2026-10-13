package fi.haagahelia.accounting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.GroupInvitation;
import fi.haagahelia.accounting.model.User;

import jakarta.persistence.LockModeType;

public interface GroupInvitationRepository
        extends JpaRepository<GroupInvitation, Long> {

    boolean existsByGroup_IdAndInvitedUser(Long groupId, User user);

    List<GroupInvitation> findByInvitedUserOrderByCreatedAtDescIdDesc(User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM GroupInvitation i WHERE i.id = :id")
    Optional<GroupInvitation> findByIdForUpdate(@Param("id") Long id);
}