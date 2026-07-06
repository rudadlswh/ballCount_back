package com.kbo.crawlerapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.live-sync")
public class LiveSyncProperties {

    private Duration schedulerInterval = Duration.ofSeconds(2);
    private Duration idleInterval = Duration.ofMinutes(30);
    private Duration pregameEligibilityWindow = Duration.ofHours(4);
    private Duration pregameCheckInterval = Duration.ofMinutes(5);
    private Duration pregameFastPollingWindow = Duration.ofMinutes(30);
    private Duration pregameFastPollingInterval = Duration.ofMinutes(1);
    private Duration livePollingInterval = Duration.ofSeconds(1);
    private Duration finalConfirmationPollingInterval = Duration.ofSeconds(30);
    private Duration pregameTtl = Duration.ofMinutes(3);
    private Duration liveTtl = Duration.ofSeconds(3);
    private Duration finalConfirmationTtl = Duration.ofSeconds(30);
    private Duration detailExtractionTimeout = Duration.ofMillis(100);

    public Duration getIdleInterval() {
        return idleInterval;
    }

    public void setIdleInterval(Duration idleInterval) {
        this.idleInterval = idleInterval;
    }

    public Duration getPregameCheckInterval() {
        return pregameCheckInterval;
    }

    public void setPregameCheckInterval(Duration pregameCheckInterval) {
        this.pregameCheckInterval = pregameCheckInterval;
    }

    public Duration getPregameFastPollingWindow() {
        return pregameFastPollingWindow;
    }

    public void setPregameFastPollingWindow(Duration pregameFastPollingWindow) {
        this.pregameFastPollingWindow = pregameFastPollingWindow;
    }

    public Duration getPregameFastPollingInterval() {
        return pregameFastPollingInterval;
    }

    public void setPregameFastPollingInterval(Duration pregameFastPollingInterval) {
        this.pregameFastPollingInterval = pregameFastPollingInterval;
    }

    public Duration getLivePollingInterval() {
        return livePollingInterval;
    }

    public void setLivePollingInterval(Duration livePollingInterval) {
        this.livePollingInterval = livePollingInterval;
    }

    public Duration getFinalConfirmationPollingInterval() {
        return finalConfirmationPollingInterval;
    }

    public void setFinalConfirmationPollingInterval(Duration finalConfirmationPollingInterval) {
        this.finalConfirmationPollingInterval = finalConfirmationPollingInterval;
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

    public Duration getDetailExtractionTimeout() {
        return detailExtractionTimeout;
    }

    public void setDetailExtractionTimeout(Duration detailExtractionTimeout) {
        this.detailExtractionTimeout = detailExtractionTimeout;
    }
}
