package fi.haagahelia.accounting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import fi.haagahelia.accounting.model.GroupMembership;
import fi.haagahelia.accounting.model.User;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {

    boolean existsByGroup_IdAndUser(Long groupId, User user);

    List<GroupMembership> findByGroup_IdOrderByJoinedAtAscIdAsc(Long groupId);

    long deleteByGroup_IdAndUser(Long groupId, User user);
}