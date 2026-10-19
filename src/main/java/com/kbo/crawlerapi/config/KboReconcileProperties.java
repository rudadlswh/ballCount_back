package com.kbo.crawlerapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kbo.reconcile")
public class KboReconcileProperties {

    private int publicMaxDates = 3;
    private int publicAllowedPastDays = 7;
    private Duration publicCooldown = Duration.ofMinutes(3);
    private int adminMaxDates = 31;

    public int getPublicMaxDates() {
        return publicMaxDates;
    }

    public void setPublicMaxDates(int publicMaxDates) {
        this.publicMaxDates = publicMaxDates;
    }

    public int getPublicAllowedPastDays() {
        return publicAllowedPastDays;
    }

    public void setPublicAllowedPastDays(int publicAllowedPastDays) {
        this.publicAllowedPastDays = publicAllowedPastDays;
    }

    public Duration getPublicCooldown() {
        return publicCooldown;
    }

    public void setPublicCooldown(Duration publicCooldown) {
        this.publicCooldown = publicCooldown;
    }

    public int getAdminMaxDates() {
        return adminMaxDates;
    }

    public void setAdminMaxDates(int adminMaxDates) {
        this.adminMaxDates = adminMaxDates;
    }
}
