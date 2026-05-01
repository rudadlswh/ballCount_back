package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_devices")
public class NotificationDevice {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String platform;

    @Column(nullable = false, length = 30)
    private String environment;

    @Column(name = "device_token", nullable = false)
    private String deviceToken;

    @Column(name = "installation_id", length = 100)
    private String installationId;

    @Column(name = "favorite_team_id", length = 30)
    private String favoriteTeamId;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    protected NotificationDevice() {
    }

    public NotificationDevice(UUID id, String platform, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime lastSeenAt) {
        this(id, platform, "sandbox", deviceToken, installationId, favoriteTeamId, notificationsEnabled, lastSeenAt);
    }

    public NotificationDevice(UUID id, String platform, String environment, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime lastSeenAt) {
        this.id = id;
        this.platform = platform;
        this.environment = environment;
        this.deviceToken = deviceToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.notificationsEnabled = notificationsEnabled;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public String getInstallationId() {
        return installationId;
    }

    public String getFavoriteTeamId() {
        return favoriteTeamId;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void update(String environment, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime seenAt) {
        update(environment, this.deviceToken, installationId, favoriteTeamId, notificationsEnabled, seenAt);
    }

    public void update(String environment, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime seenAt) {
        this.environment = environment;
        this.deviceToken = deviceToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.notificationsEnabled = notificationsEnabled;
        this.lastSeenAt = seenAt;
    }

    public void disable(OffsetDateTime seenAt) {
        this.notificationsEnabled = false;
        this.lastSeenAt = seenAt;
    }
}
