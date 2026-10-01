package com.tradesentry.agent.idempotency;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent,ProcessedEventId> {
    @Modifying
    @Query(value="insert into processed_events(event_id,consumer_name,processed_at) values (:eventId,:consumerName,:processedAt) on conflict (event_id,consumer_name) do nothing",nativeQuery=true)
    int insertIfAbsent(@Param("eventId") UUID eventId,@Param("consumerName") String consumerName,
                       @Param("processedAt") Instant processedAt);
    default boolean claim(UUID eventId,String consumer){return insertIfAbsent(eventId,consumer,Instant.now())==1;}
}
