package com.kbo.crawlerapi.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.kbo.crawlerapi.domain.NotificationEvent;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {

    @EntityGraph(attributePaths = {"game", "game.homeTeam", "game.awayTeam"})
    Optional<NotificationEvent> findById(UUID id);

    Optional<NotificationEvent> findByEventKey(String eventKey);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM notification_events WHERE event_key = :eventKey)
                OR EXISTS (SELECT 1 FROM notification_event_keys WHERE event_key = :eventKey)
            """, nativeQuery = true)
    boolean existsByEventKey(@Param("eventKey") String eventKey);

    long countByGame_Id(UUID gameId);
}
