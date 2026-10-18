package com.flowcortex.user.phone;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Verified phone numbers, keyed by user. */
public interface UserPhoneRepository extends JpaRepository<UserPhone, UUID> {

    boolean existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(String phoneE164, UUID userId);
}
