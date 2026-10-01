package com.tradesentry.core.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    @Query(value="select * from outbox_events where status='PENDING' order by created_at for update skip locked limit 100",nativeQuery=true)
    List<OutboxEvent> lockPendingBatch();
}
