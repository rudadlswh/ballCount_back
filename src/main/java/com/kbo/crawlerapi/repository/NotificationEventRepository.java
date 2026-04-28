package com.kbo.crawlerapi.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kbo.crawlerapi.domain.NotificationEvent;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {

    Optional<NotificationEvent> findByEventKey(String eventKey);
}
