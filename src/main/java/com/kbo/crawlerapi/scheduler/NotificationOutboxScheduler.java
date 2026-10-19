package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.repository.NotificationOutboxRepository;
import com.kbo.crawlerapi.service.NotificationEventService;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxScheduler {
    private final NotificationOutboxRepository outbox;
    private final NotificationEventService delivery;
    private final SyncProperties sync;
    private final Clock clock;
    private final Executor executor;

    public NotificationOutboxScheduler(NotificationOutboxRepository outbox, NotificationEventService delivery,
                                        SyncProperties sync, Clock clock, @Qualifier("notificationDeliveryExecutor") Executor executor) {
        this.outbox = outbox; this.delivery = delivery; this.sync = sync; this.clock = clock; this.executor = executor;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void recover() {
        if (!sync.isEnabled()) return;
        for (var eventId : outbox.due(OffsetDateTime.now(clock))) {
            try { executor.execute(() -> delivery.deliverDurable(eventId)); }
            catch (RejectedExecutionException exception) { break; }
        }
    }
}
