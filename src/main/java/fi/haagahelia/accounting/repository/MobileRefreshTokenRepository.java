package fi.haagahelia.accounting.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fi.haagahelia.accounting.model.MobileRefreshToken;

public interface MobileRefreshTokenRepository extends JpaRepository<MobileRefreshToken, String> {

    @Modifying
    @Query("delete from MobileRefreshToken t where t.expiresAt <= :now")
    void deleteExpired(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MobileRefreshToken t where t.tokenHash = :hash")
    void revoke(@Param("hash") String hash);
}
