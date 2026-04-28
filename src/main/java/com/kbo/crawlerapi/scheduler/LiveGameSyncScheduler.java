package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LiveGameSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiveGameSyncScheduler.class);

    private final LiveGameSyncService liveGameSyncService;
    private final LiveSyncProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LiveGameSyncScheduler(LiveGameSyncService liveGameSyncService, LiveSyncProperties properties) {
        this.liveGameSyncService = liveGameSyncService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.live-sync.scheduler-interval:PT60S}")
    public void runTick() {
        log.info("[LiveGameSync] scheduler tick");
        if (!properties.isEnabled()) {
            log.info("[LiveGameSync] skipped disabled");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("[LiveGameSync] scheduler tick skipped because previous run is still active");
            return;
        }
        try {
            liveGameSyncService.syncToday();
        } finally {
            running.set(false);
        }
    }
}
