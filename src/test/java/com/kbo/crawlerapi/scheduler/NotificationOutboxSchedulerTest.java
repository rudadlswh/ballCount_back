package com.kbo.crawlerapi.scheduler;

import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.repository.NotificationOutboxRepository;
import com.kbo.crawlerapi.service.NotificationEventService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationOutboxSchedulerTest {
    @Test
    void skipsWinterAndExplicitlyDisabledRuntimeThenResumesDeliveryInMarch() {
        var outbox = mock(NotificationOutboxRepository.class);
        var delivery = mock(NotificationEventService.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        var clock = mock(Clock.class);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        when(clock.instant()).thenReturn(Instant.parse("2027-02-28T14:59:59Z"));
        var scheduler = new NotificationOutboxScheduler(outbox, delivery, sync, clock, Runnable::run);
        scheduler.recover();
        verifyNoInteractions(outbox, delivery);

        when(clock.instant()).thenReturn(Instant.parse("2027-02-28T15:00:00Z"));
        sync.setEnabled(false);
        scheduler.recover();
        verifyNoInteractions(outbox, delivery);
        sync.setEnabled(true);
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        var eventId = UUID.randomUUID();
        when(outbox.due(any())).thenReturn(List.of(eventId));
        scheduler.recover();
        verify(delivery).deliverDurable(eventId);
    }
}
