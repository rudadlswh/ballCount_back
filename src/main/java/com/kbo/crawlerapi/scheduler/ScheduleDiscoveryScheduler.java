package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.service.DateSyncLockService;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduleDiscoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleDiscoveryScheduler.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(30);
    private final KboScheduleImportService importer;
    private final DateSyncLockService locks;
    private final SyncProperties sync;
    private final Clock clock;
    private Instant lastSucceededAt;
    private YearMonth lastMonth;

    public ScheduleDiscoveryScheduler(KboScheduleImportService importer, DateSyncLockService locks,
                                      SyncProperties sync, Clock clock) {
        this.importer = importer;
        this.locks = locks;
        this.sync = sync;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void discover() {
        if (!sync.isEnabled()) return;
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        YearMonth month = YearMonth.from(today);
        if (month.equals(lastMonth) && lastSucceededAt != null
                && clock.instant().isBefore(lastSucceededAt.plus(REFRESH_INTERVAL))) return;
        try (var lock = locks.tryLock(today)) {
            if (!lock.acquired()) return;
            importer.importMonthlySchedule(month);
            importer.importMonthlySchedule(month.plusMonths(1));
            lastSucceededAt = clock.instant();
            lastMonth = month;
            log.info("[ScheduleDiscovery] refreshed month={} nextMonth={} lastSucceededAt={}", month, month.plusMonths(1), lastSucceededAt);
        } catch (RuntimeException exception) {
            log.warn("[ScheduleDiscovery] refresh failed month={} retryOnNextTick=true", month, exception);
        }
    }
}
