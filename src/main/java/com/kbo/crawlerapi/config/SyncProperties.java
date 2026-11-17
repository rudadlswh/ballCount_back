package com.kbo.crawlerapi.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sync")
public class SyncProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOffSeason(Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        int month = today.getMonthValue();
        // The 2026 season has ended; keep future November postseasons enabled.
        boolean season2026Ended = !today.isBefore(LocalDate.of(2026, 11, 17))
                && today.isBefore(LocalDate.of(2027, 3, 1));
        return season2026Ended || month == 12 || month <= 2;
    }

    public boolean isActive(Clock clock) {
        return enabled && !isOffSeason(clock);
    }
}
