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
        int month = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getMonthValue();
        return month == 12 || month <= 2;
    }

    public boolean isActive(Clock clock) {
        return enabled && !isOffSeason(clock);
    }
}
