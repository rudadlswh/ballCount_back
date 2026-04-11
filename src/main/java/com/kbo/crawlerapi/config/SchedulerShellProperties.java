package com.kbo.crawlerapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService.RefreshPhase;

@ConfigurationProperties(prefix = "app.scheduler")
public class SchedulerShellProperties {

    private boolean enabled = false;
    private boolean pregameEnabled = true;
    private boolean liveEnabled = false;
    private boolean postFinalEnabled = true;
    private Duration pregameInterval = Duration.ofMinutes(30);
    private Duration liveInterval = Duration.ofSeconds(15);
    private Duration postFinalInterval = Duration.ofSeconds(60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPregameEnabled() {
        return pregameEnabled;
    }

    public void setPregameEnabled(boolean pregameEnabled) {
        this.pregameEnabled = pregameEnabled;
    }

    public boolean isLiveEnabled() {
        return liveEnabled;
    }

    public void setLiveEnabled(boolean liveEnabled) {
        this.liveEnabled = liveEnabled;
    }

    public boolean isPostFinalEnabled() {
        return postFinalEnabled;
    }

    public void setPostFinalEnabled(boolean postFinalEnabled) {
        this.postFinalEnabled = postFinalEnabled;
    }

    public Duration getPregameInterval() {
        return pregameInterval;
    }

    public void setPregameInterval(Duration pregameInterval) {
        this.pregameInterval = pregameInterval;
    }

    public Duration getLiveInterval() {
        return liveInterval;
    }

    public void setLiveInterval(Duration liveInterval) {
        this.liveInterval = liveInterval;
    }

    public Duration getPostFinalInterval() {
        return postFinalInterval;
    }

    public void setPostFinalInterval(Duration postFinalInterval) {
        this.postFinalInterval = postFinalInterval;
    }

    public Duration intervalFor(RefreshPhase phase) {
        return switch (phase) {
            case PREGAME -> pregameInterval;
            case LIVE -> liveInterval;
            case POST_FINAL -> postFinalInterval;
            default -> throw new IllegalArgumentException("No scheduler interval exists for phase: " + phase);
        };
    }
}
