package com.flowcortex.user.phone;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Verified phone numbers, keyed by user. */
public interface UserPhoneRepository extends JpaRepository<UserPhone, UUID> {

    boolean existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(String phoneE164, UUID userId);

    /** Puts {@code restored} back only while the member's number is still {@code expected}; 0 rows means it moved. */
    @Modifying
    @Query("UPDATE UserPhone p SET p.phoneE164 = :restored, p.verifiedAt = :now "
            + "WHERE p.userId = :userId AND p.phoneE164 = :expected AND p.verifiedAt IS NOT NULL")
    int restoreNumber(@Param("userId") UUID userId, @Param("expected") String expected,
            @Param("restored") String restored, @Param("now") Instant now);
}
