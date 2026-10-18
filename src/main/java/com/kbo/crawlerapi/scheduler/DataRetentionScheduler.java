package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.service.DataRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private final DataRetentionService retention;
    private final SyncProperties sync;
    private final boolean enabled;

    public DataRetentionScheduler(DataRetentionService retention, SyncProperties sync,
                                  @Value("${app.retention.enabled:true}") boolean enabled) {
        this.retention = retention;
        this.sync = sync;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void cleanup() {
        if (!enabled || !sync.isEnabled()) return;
        try {
            var result = retention.cleanup();
            if (result.total() > 0) log.info("[DataRetention] deleted={}", result);
        } catch (RuntimeException exception) {
            log.warn("[DataRetention] cleanup failed; retry on next minute", exception);
        }
    }
}
