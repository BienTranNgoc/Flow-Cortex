package com.flowcortex.user.phone;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The development SMS sink. */
public interface SmsOutboxRepository extends JpaRepository<SmsOutboxEntry, UUID> {

    List<SmsOutboxEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
