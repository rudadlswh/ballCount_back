package com.kbo.crawlerapi.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.service.DataRetentionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DataRetentionSchedulerTest {
    private final DataRetentionService retention = mock(DataRetentionService.class);
    private final SyncProperties sync = new SyncProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledOrNonWriterRuntimeDoesNotDeleteAnything() {
        new DataRetentionScheduler(retention, sync, true, clock).cleanup();
        sync.setEnabled(true);
        new DataRetentionScheduler(retention, sync, false, clock).cleanup();
        verifyNoInteractions(retention);
    }

    @Test
    void failureAllowsTheNextScheduledRunToRetry() {
        sync.setEnabled(true);
        when(retention.cleanup()).thenThrow(new IllegalStateException("test failure"))
                .thenReturn(new DataRetentionService.CleanupResult(1, 0, 0, 0, 0, 0));
        var scheduler = new DataRetentionScheduler(retention, sync, true, clock);
        assertThatCode(scheduler::cleanup).doesNotThrowAnyException();
        scheduler.cleanup();
        verify(retention, times(2)).cleanup();
    }

    @Test
    void pausesCleanupDuringWinterAndResumesInMarch() {
        sync.setEnabled(true);
        var changingClock = mock(Clock.class);
        when(changingClock.withZone(any())).thenAnswer(invocation -> Clock.fixed(changingClock.instant(), invocation.getArgument(0)));
        when(changingClock.instant()).thenReturn(Instant.parse("2027-02-28T14:59:59Z"));
        var scheduler = new DataRetentionScheduler(retention, sync, true, changingClock);
        scheduler.cleanup();
        verifyNoInteractions(retention);
        when(changingClock.instant()).thenReturn(Instant.parse("2027-02-28T15:00:00Z"));
        when(retention.cleanup()).thenReturn(new DataRetentionService.CleanupResult(0, 0, 0, 0, 0, 0));
        scheduler.cleanup();
        verify(retention).cleanup();
    }
}
