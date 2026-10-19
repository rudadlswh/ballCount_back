package com.kbo.crawlerapi.scheduler;

import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.service.DateSyncLockService;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import java.time.*;
import org.junit.jupiter.api.Test;

class ScheduleDiscoverySchedulerTest {
    @Test
    void discoversWithoutStoredGamesThrottlesSuccessAndRefreshesNextMonth() {
        var importer = mock(KboScheduleImportService.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T15:30:00Z"), ZoneOffset.UTC);
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock);
        scheduler.discover(); scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 11));
        verifyNoMoreInteractions(importer);
    }

    @Test
    void failedFetchRetriesAndDisabledReaderMakesNoRequests() {
        var importer = mock(KboScheduleImportService.class);
        var sync = new SyncProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-12-31T16:00:00Z"), ZoneOffset.UTC);
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock);
        scheduler.discover(); verifyNoInteractions(importer);
        sync.setEnabled(true);
        when(importer.importMonthlySchedule(YearMonth.of(2027, 1))).thenThrow(new IllegalStateException("upstream timeout")).thenReturn(null);
        scheduler.discover(); scheduler.discover();
        verify(importer, times(2)).importMonthlySchedule(YearMonth.of(2027, 1));
        verify(importer).importMonthlySchedule(YearMonth.of(2027, 2));
    }

    @Test
    void busySyncLockDefersDiscoveryWithoutLosingTheNextAttempt() {
        var importer = mock(KboScheduleImportService.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);
        var locks = new DateSyncLockService();
        var scheduler = new ScheduleDiscoveryScheduler(importer, locks, sync, clock);
        try (var lock = locks.tryLock(LocalDate.of(2026, 10, 16))) {
            scheduler.discover(); verifyNoInteractions(importer);
        }
        scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));
    }
}
