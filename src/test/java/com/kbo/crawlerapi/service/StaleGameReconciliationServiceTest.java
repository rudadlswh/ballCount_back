package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kbo.crawlerapi.api.InvalidParameterException;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class StaleGameReconciliationServiceTest {
    private final MutableClock clock = new MutableClock();
    private final LiveGameSyncService sync = mock(LiveGameSyncService.class);
    private final StaleGameReconciliationService service = new StaleGameReconciliationService(sync, publicProperties(), clock);
    private final LocalDate today = LocalDate.now(clock);

    @Test
    void rejectsUnboundedDuplicateNullAndOutOfWindowInputsBeforeWork() {
        for (List<LocalDate> dates : Arrays.asList(null, List.<LocalDate>of(),
                List.of(today, today, today, today), Arrays.asList(today, null),
                List.of(today.plusDays(1)), List.of(today.minusDays(367)))) {
            assertThatThrownBy(() -> service.reconcilePublic(dates)).isInstanceOf(InvalidParameterException.class);
        }
        verifyNoInteractions(sync);
    }

    @Test
    void permitsThreeBoundedDatesAndRespectsGlobalCooldownAcrossDifferentDates() {
        when(sync.sync(any(), eq(false))).thenAnswer(invocation -> summary(invocation.getArgument(0)));
        var result = service.reconcilePublic(List.of(today, today.minusDays(1), today.minusDays(366)));
        assertThat(result.processedDateCount()).isEqualTo(3);
        assertThatThrownBy(() -> service.reconcilePublic(List.of(today.minusDays(2))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(429));
        clock.now = clock.now.plusSeconds(30);
        assertThat(service.reconcilePublic(List.of(today.minusDays(2))).processedDateCount()).isEqualTo(1);
        verify(sync, times(4)).sync(any(), eq(false));
        verify(sync, never()).sync(any(), eq(true));
    }

    @Test
    void concurrentDifferentDatesFailFastEvenAfterCooldownElapsed() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(sync.sync(any(), eq(false))).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return summary(invocation.getArgument(0));
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.reconcilePublic(List.of(today)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            clock.now = clock.now.plusSeconds(60);
            assertThatThrownBy(() -> service.reconcilePublic(List.of(today.minusDays(1))))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(service.reconcilePublic(List.of(today)).skippedAlreadyInProgressCount()).isEqualTo(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).processedDateCount()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failuresKeepCooldownAndReleaseConcurrencySlot() {
        when(sync.sync(today, false)).thenThrow(new IllegalStateException("upstream unavailable"));
        assertThat(service.reconcilePublic(List.of(today)).failedCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.reconcilePublic(List.of(today))).isInstanceOf(ResponseStatusException.class);
        clock.now = clock.now.plusSeconds(30);
        doReturn(summary(today)).when(sync).sync(today, false);
        assertThat(service.reconcilePublic(List.of(today)).processedDateCount()).isEqualTo(1);
    }

    private static com.kbo.crawlerapi.config.KboReconcileProperties publicProperties() {
        var properties = new com.kbo.crawlerapi.config.KboReconcileProperties();
        properties.setPublicAllowedPastDays(366);
        properties.setPublicCooldown(Duration.ofSeconds(30));
        return properties;
    }

    private static LiveGameSyncService.LiveSyncSummary summary(LocalDate date) {
        return new LiveGameSyncService.LiveSyncSummary(date, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
    }

    private static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-19T00:00:00Z");
        public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
}
