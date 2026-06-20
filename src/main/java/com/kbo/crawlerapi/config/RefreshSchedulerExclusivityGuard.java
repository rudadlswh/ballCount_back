package com.kbo.crawlerapi.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class RefreshSchedulerExclusivityGuard {

    private final LiveSyncProperties liveSyncProperties;
    private final SchedulerShellProperties schedulerShellProperties;

    public RefreshSchedulerExclusivityGuard(
            LiveSyncProperties liveSyncProperties,
            SchedulerShellProperties schedulerShellProperties
    ) {
        this.liveSyncProperties = liveSyncProperties;
        this.schedulerShellProperties = schedulerShellProperties;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (liveSyncProperties.isEnabled() && schedulerShellProperties.isEnabled()) {
            throw new IllegalStateException(
                    "Live sync scheduler and detail refresh scheduler cannot both be enabled. "
                            + "Disable either app.live-sync.enabled or app.scheduler.enabled."
            );
        }
    }
}
