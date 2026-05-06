package com.kbo.crawlerapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.live-sync")
public class LiveSyncProperties {

    private boolean enabled = false;
    private Duration schedulerInterval = Duration.ofSeconds(10);
    private Duration pregameEligibilityWindow = Duration.ofHours(4);
    private Duration pregameTtl = Duration.ofMinutes(3);
    private Duration liveTtl = Duration.ofSeconds(30);
    private Duration finalConfirmationTtl = Duration.ofMinutes(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSchedulerInterval() {
        return schedulerInterval;
    }

    public void setSchedulerInterval(Duration schedulerInterval) {
        this.schedulerInterval = schedulerInterval;
    }

    public Duration getPregameEligibilityWindow() {
        return pregameEligibilityWindow;
    }

    public void setPregameEligibilityWindow(Duration pregameEligibilityWindow) {
        this.pregameEligibilityWindow = pregameEligibilityWindow;
    }

    public Duration getPregameTtl() {
        return pregameTtl;
    }

    public void setPregameTtl(Duration pregameTtl) {
        this.pregameTtl = pregameTtl;
    }

    public Duration getLiveTtl() {
        return liveTtl;
    }

    public void setLiveTtl(Duration liveTtl) {
        this.liveTtl = liveTtl;
    }

    public Duration getFinalConfirmationTtl() {
        return finalConfirmationTtl;
    }

    public void setFinalConfirmationTtl(Duration finalConfirmationTtl) {
        this.finalConfirmationTtl = finalConfirmationTtl;
    }
}
