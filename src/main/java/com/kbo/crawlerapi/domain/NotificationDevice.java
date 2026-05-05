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

    @Column(name = "game_start_enabled", nullable = false)
    private boolean gameStartEnabled = true;

    @Column(name = "score_change_enabled", nullable = false)
    private boolean scoreChangeEnabled = true;

    @Column(name = "lead_change_enabled", nullable = false)
    private boolean leadChangeEnabled = true;

    @Column(name = "game_end_enabled", nullable = false)
    private boolean gameEndEnabled = true;

    @Column(name = "on_base_enabled", nullable = false)
    private boolean onBaseEnabled = false;

    @Column(name = "inning_change_enabled", nullable = false)
    private boolean inningChangeEnabled = false;

    @Column(name = "favorite_team_only_enabled", nullable = false)
    private boolean favoriteTeamOnlyEnabled = false;

    @Column(name = "mute_when_losing_enabled", nullable = false)
    private boolean muteWhenLosingEnabled = false;

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
        this(
                id,
                platform,
                environment,
                deviceToken,
                installationId,
                favoriteTeamId,
                notificationsEnabled,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                lastSeenAt
        );
    }

    public NotificationDevice(
            UUID id,
            String platform,
            String environment,
            String deviceToken,
            String installationId,
            String favoriteTeamId,
            boolean notificationsEnabled,
            boolean gameStartEnabled,
            boolean scoreChangeEnabled,
            boolean leadChangeEnabled,
            boolean gameEndEnabled,
            boolean onBaseEnabled,
            boolean inningChangeEnabled,
            boolean favoriteTeamOnlyEnabled,
            boolean muteWhenLosingEnabled,
            OffsetDateTime lastSeenAt
    ) {
        this.id = id;
        this.platform = platform;
        this.environment = environment;
        this.deviceToken = deviceToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.notificationsEnabled = notificationsEnabled;
        this.gameStartEnabled = gameStartEnabled;
        this.scoreChangeEnabled = scoreChangeEnabled;
        this.leadChangeEnabled = leadChangeEnabled;
        this.gameEndEnabled = gameEndEnabled;
        this.onBaseEnabled = onBaseEnabled;
        this.inningChangeEnabled = inningChangeEnabled;
        this.favoriteTeamOnlyEnabled = favoriteTeamOnlyEnabled;
        this.muteWhenLosingEnabled = muteWhenLosingEnabled;
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

    public boolean isGameStartEnabled() {
        return gameStartEnabled;
    }

    public boolean isScoreChangeEnabled() {
        return scoreChangeEnabled;
    }

    public boolean isLeadChangeEnabled() {
        return leadChangeEnabled;
    }

    public boolean isGameEndEnabled() {
        return gameEndEnabled;
    }

    public boolean isOnBaseEnabled() {
        return onBaseEnabled;
    }

    public boolean isInningChangeEnabled() {
        return inningChangeEnabled;
    }

    public boolean isFavoriteTeamOnlyEnabled() {
        return favoriteTeamOnlyEnabled;
    }

    public boolean isMuteWhenLosingEnabled() {
        return muteWhenLosingEnabled;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void update(String environment, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime seenAt) {
        update(environment, this.deviceToken, installationId, favoriteTeamId, notificationsEnabled, seenAt);
    }

    public void update(String environment, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime seenAt) {
        update(this.platform, environment, deviceToken, installationId, favoriteTeamId, notificationsEnabled, seenAt);
    }

    public void update(String platform, String environment, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled, OffsetDateTime seenAt) {
        update(
                platform,
                environment,
                deviceToken,
                installationId,
                favoriteTeamId,
                notificationsEnabled,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                seenAt
        );
    }

    public void update(
            String platform,
            String environment,
            String deviceToken,
            String installationId,
            String favoriteTeamId,
            boolean notificationsEnabled,
            boolean gameStartEnabled,
            boolean scoreChangeEnabled,
            boolean leadChangeEnabled,
            boolean gameEndEnabled,
            boolean onBaseEnabled,
            boolean inningChangeEnabled,
            boolean favoriteTeamOnlyEnabled,
            boolean muteWhenLosingEnabled,
            OffsetDateTime seenAt
    ) {
        this.platform = platform;
        this.environment = environment;
        this.deviceToken = deviceToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.notificationsEnabled = notificationsEnabled;
        this.gameStartEnabled = gameStartEnabled;
        this.scoreChangeEnabled = scoreChangeEnabled;
        this.leadChangeEnabled = leadChangeEnabled;
        this.gameEndEnabled = gameEndEnabled;
        this.onBaseEnabled = onBaseEnabled;
        this.inningChangeEnabled = inningChangeEnabled;
        this.favoriteTeamOnlyEnabled = favoriteTeamOnlyEnabled;
        this.muteWhenLosingEnabled = muteWhenLosingEnabled;
        this.lastSeenAt = seenAt;
    }

    public void disable(OffsetDateTime seenAt) {
        this.notificationsEnabled = false;
        this.lastSeenAt = seenAt;
    }
}
