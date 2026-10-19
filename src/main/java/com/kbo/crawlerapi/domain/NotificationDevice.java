package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
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

    @Column(name = "monitored_game_id", length = 200)
    private String monitoredGameId;

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

    @Column(name = "rain_delay_enabled", nullable = false)
    private boolean rainDelayEnabled = true;

    @Column(name = "quiet_hours_enabled", nullable = false)
    private boolean quietHoursEnabled = false;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "quiet_hours_start_hour", nullable = false)
    private int quietHoursStartHour = 23;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "quiet_hours_end_hour", nullable = false)
    private int quietHoursEndHour = 7;

    @Column(name = "notification_authorization_status", nullable = false, length = 30)
    private String notificationAuthorizationStatus = "not_determined";

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
                true,
                false,
                23,
                7,
                notificationsEnabled ? "authorized" : "denied",
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
        this(
                id, platform, environment, deviceToken, installationId, favoriteTeamId, notificationsEnabled,
                gameStartEnabled, scoreChangeEnabled, leadChangeEnabled, gameEndEnabled, onBaseEnabled,
                inningChangeEnabled, favoriteTeamOnlyEnabled, muteWhenLosingEnabled, true, false, 23, 7,
                notificationsEnabled ? "authorized" : "denied", lastSeenAt
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
            boolean rainDelayEnabled,
            boolean quietHoursEnabled,
            int quietHoursStartHour,
            int quietHoursEndHour,
            String notificationAuthorizationStatus,
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
        this.rainDelayEnabled = rainDelayEnabled;
        this.quietHoursEnabled = quietHoursEnabled;
        this.quietHoursStartHour = quietHoursStartHour;
        this.quietHoursEndHour = quietHoursEndHour;
        this.notificationAuthorizationStatus = notificationAuthorizationStatus;
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

    public String getMonitoredGameId() {
        return monitoredGameId;
    }

    public void updateMonitoredGameId(String monitoredGameId) {
        this.monitoredGameId = monitoredGameId;
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

    public boolean isRainDelayEnabled() {
        return rainDelayEnabled;
    }

    public boolean isQuietHoursEnabled() {
        return quietHoursEnabled;
    }

    public int getQuietHoursStartHour() {
        return quietHoursStartHour;
    }

    public int getQuietHoursEndHour() {
        return quietHoursEndHour;
    }

    public String getNotificationAuthorizationStatus() {
        return notificationAuthorizationStatus;
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
                true,
                false,
                23,
                7,
                notificationsEnabled ? "authorized" : "denied",
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
        update(
                platform, environment, deviceToken, installationId, favoriteTeamId, notificationsEnabled,
                gameStartEnabled, scoreChangeEnabled, leadChangeEnabled, gameEndEnabled, onBaseEnabled,
                inningChangeEnabled, favoriteTeamOnlyEnabled, muteWhenLosingEnabled, true, false, 23, 7,
                notificationsEnabled ? "authorized" : "denied", seenAt
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
            boolean rainDelayEnabled,
            boolean quietHoursEnabled,
            int quietHoursStartHour,
            int quietHoursEndHour,
            String notificationAuthorizationStatus,
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
        this.rainDelayEnabled = rainDelayEnabled;
        this.quietHoursEnabled = quietHoursEnabled;
        this.quietHoursStartHour = quietHoursStartHour;
        this.quietHoursEndHour = quietHoursEndHour;
        this.notificationAuthorizationStatus = notificationAuthorizationStatus;
        this.lastSeenAt = seenAt;
    }

    public void disable(OffsetDateTime seenAt) {
        this.notificationsEnabled = false;
        this.lastSeenAt = seenAt;
    }
}
