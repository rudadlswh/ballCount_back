package com.kbo.crawlerapi.scheduler;

import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.DateSyncLockService;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScheduleDiscoverySchedulerTest {
    @Test
    void discoversWithoutStoredGamesThrottlesSuccessAndRefreshesNextMonth() {
        var importer = mock(KboScheduleImportService.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T15:30:00Z"), ZoneOffset.UTC);
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, mock(GameRepository.class));
        scheduler.discover(); scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 11));
        verifyNoMoreInteractions(importer);
    }

    @Test
    void failedFetchRetriesAndDisabledReaderMakesNoRequests() {
        var importer = mock(KboScheduleImportService.class);
        var sync = new SyncProperties();
        Clock clock = Clock.fixed(Instant.parse("2027-03-31T16:00:00Z"), ZoneOffset.UTC);
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, mock(GameRepository.class));
        scheduler.discover(); verifyNoInteractions(importer);
        sync.setEnabled(true);
        when(importer.importMonthlySchedule(YearMonth.of(2027, 4))).thenThrow(new IllegalStateException("upstream timeout")).thenReturn(null);
        scheduler.discover(); scheduler.discover();
        verify(importer, times(2)).importMonthlySchedule(YearMonth.of(2027, 4));
        verify(importer).importMonthlySchedule(YearMonth.of(2027, 5));
    }

    @Test
    void busySyncLockDefersDiscoveryWithoutLosingTheNextAttempt() {
        var importer = mock(KboScheduleImportService.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);
        var locks = new DateSyncLockService();
        var scheduler = new ScheduleDiscoveryScheduler(importer, locks, sync, clock, mock(GameRepository.class));
        try (var lock = locks.tryLock(LocalDate.of(2026, 10, 16))) {
            scheduler.discover(); verifyNoInteractions(importer);
        }
        scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));
    }

    @Test
    void offDayStopsRepeatedImportsAndChecksAgainAtKoreanMidnight() {
        var importer = mock(KboScheduleImportService.class);
        var games = mock(GameRepository.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        var clock = mock(Clock.class);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T14:00:00Z"));
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, games);

        scheduler.discover();
        clearInvocations(importer);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T14:30:00Z"));
        scheduler.discover();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T14:59:00Z"));
        scheduler.discover();
        verifyNoInteractions(importer);
        verify(games).findByGameDateOrderByScheduledAtAscPublicGameIdAsc(LocalDate.of(2026, 10, 16));

        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T15:00:00Z"));
        scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 11));
    }

    @Test
    void newlyAddedGameResumesThirtyMinuteRefreshes() {
        var importer = mock(KboScheduleImportService.class);
        var games = mock(GameRepository.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        var clock = mock(Clock.class);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, games);
        scheduler.discover();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:30:00Z"));
        scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));

        var scheduledGame = game(GameStatus.SCHEDULED);
        when(games.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(LocalDate.of(2026, 10, 16)))
                .thenReturn(List.of(scheduledGame));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:00:00Z"));
        scheduler.discover();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:29:00Z"));
        scheduler.discover();
        verify(importer, times(2)).importMonthlySchedule(YearMonth.of(2026, 10));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:30:00Z"));
        scheduler.discover();
        verify(importer, times(3)).importMonthlySchedule(YearMonth.of(2026, 10));
        verify(importer, times(3)).importMonthlySchedule(YearMonth.of(2026, 11));
    }

    @Test
    void stopsRepeatedDiscoveryAfterAllScheduledGamesAreCancelled() {
        var importer = mock(KboScheduleImportService.class);
        var games = mock(GameRepository.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        var clock = mock(Clock.class);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, games);
        scheduler.discover();

        var scheduledGame = game(GameStatus.SCHEDULED);
        var cancelledGames = List.of(game(GameStatus.CANCELLED), game(GameStatus.CANCELLED));
        when(games.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(LocalDate.of(2026, 10, 16)))
                .thenReturn(List.of(scheduledGame))
                .thenReturn(cancelledGames);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:30:00Z"));
        scheduler.discover();
        verify(importer, times(2)).importMonthlySchedule(YearMonth.of(2026, 10));
        clearInvocations(importer);

        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:00:00Z"));
        scheduler.discover();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:30:00Z"));
        scheduler.discover();
        verifyNoInteractions(importer);
    }

    @ParameterizedTest
    @EnumSource(GameStatus.class)
    void cancelledGamesOnlyStopDiscoveryWhenNoOtherGameNeedsSync(GameStatus status) {
        var importer = mock(KboScheduleImportService.class);
        var games = mock(GameRepository.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        var clock = mock(Clock.class);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, games);
        scheduler.discover();
        clearInvocations(importer);
        var remaining = game(status);
        var cancelledGame = game(GameStatus.CANCELLED);
        when(games.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(LocalDate.of(2026, 10, 16)))
                .thenReturn(List.of(cancelledGame, remaining));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:30:00Z"));
        scheduler.discover();

        switch (status) {
            case CANCELLED, POSTPONED, SUSPENDED -> verifyNoInteractions(importer);
            default -> {
                verify(importer).importMonthlySchedule(YearMonth.of(2026, 10));
                verify(importer).importMonthlySchedule(YearMonth.of(2026, 11));
            }
        }
        if (status == GameStatus.FINAL) {
            clearInvocations(importer);
            when(remaining.getFinalConfirmedAt()).thenReturn(OffsetDateTime.parse("2026-10-16T12:40:00+09:00"));
            when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:00:00Z"));
            scheduler.discover();
            verifyNoInteractions(importer);
        }
    }

    @Test
    void winterMakesNoDatabaseOrCrawlRequestsAndMarchResumesDiscovery() {
        var importer = mock(KboScheduleImportService.class);
        var games = mock(GameRepository.class);
        var sync = new SyncProperties(); sync.setEnabled(true);
        var clock = mock(Clock.class);
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-30T15:00:00Z"));
        var scheduler = new ScheduleDiscoveryScheduler(importer, new DateSyncLockService(), sync, clock, games);
        scheduler.discover();
        verifyNoInteractions(importer, games);

        when(clock.instant()).thenReturn(Instant.parse("2027-02-28T15:00:00Z"));
        scheduler.discover();
        verify(importer).importMonthlySchedule(YearMonth.of(2027, 3));
        verify(importer).importMonthlySchedule(YearMonth.of(2027, 4));
    }

    private static Game game(GameStatus status) {
        var game = mock(Game.class);
        when(game.getStatus()).thenReturn(status);
        return game;
    }
}
