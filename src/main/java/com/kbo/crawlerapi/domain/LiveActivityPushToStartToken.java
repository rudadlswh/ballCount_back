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
@Table(name = "live_activity_push_to_start_tokens")
public class LiveActivityPushToStartToken {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String platform;

    @Column(nullable = false, length = 30)
    private String environment;

    @Column(name = "push_to_start_token", nullable = false)
    private String pushToStartToken;

    @Column(name = "installation_id", nullable = false, length = 100)
    private String installationId;

    @Column(name = "favorite_team_id", length = 30)
    private String favoriteTeamId;

    @Column(name = "notifications_authorized", nullable = false)
    private boolean notificationsAuthorized;

    @Column(name = "live_activities_enabled", nullable = false)
    private boolean liveActivitiesEnabled;

    @Column(name = "live_activity_auto_start_enabled", nullable = false)
    private boolean liveActivityAutoStartEnabled;

    @Column(name = "game_start_enabled", nullable = false)
    private boolean gameStartEnabled;

    @Column(name = "favorite_team_only_enabled", nullable = false)
    private boolean favoriteTeamOnlyEnabled;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_started_game_key", length = 160)
    private String lastStartedGameKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    protected LiveActivityPushToStartToken() {
    }

    public LiveActivityPushToStartToken(UUID id, OffsetDateTime lastSeenAt) {
        this.id = id;
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

    public String getPushToStartToken() {
        return pushToStartToken;
    }

    public String getInstallationId() {
        return installationId;
    }

    public String getFavoriteTeamId() {
        return favoriteTeamId;
    }

    public boolean isNotificationsAuthorized() {
        return notificationsAuthorized;
    }

    public boolean isLiveActivitiesEnabled() {
        return liveActivitiesEnabled;
    }

    public boolean isLiveActivityAutoStartEnabled() {
        return liveActivityAutoStartEnabled;
    }

    public boolean isGameStartEnabled() {
        return gameStartEnabled;
    }

    public boolean isFavoriteTeamOnlyEnabled() {
        return favoriteTeamOnlyEnabled;
    }

    public boolean isActive() {
        return active;
    }

    public String getLastStartedGameKey() {
        return lastStartedGameKey;
    }

    public void update(
            String platform,
            String environment,
            String pushToStartToken,
            String installationId,
            String favoriteTeamId,
            boolean notificationsAuthorized,
            boolean liveActivitiesEnabled,
            boolean liveActivityAutoStartEnabled,
            boolean gameStartEnabled,
            boolean favoriteTeamOnlyEnabled,
            OffsetDateTime seenAt
    ) {
        this.platform = platform;
        this.environment = environment;
        this.pushToStartToken = pushToStartToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.notificationsAuthorized = notificationsAuthorized;
        this.liveActivitiesEnabled = liveActivitiesEnabled;
        this.liveActivityAutoStartEnabled = liveActivityAutoStartEnabled;
        this.gameStartEnabled = gameStartEnabled;
        this.favoriteTeamOnlyEnabled = favoriteTeamOnlyEnabled;
        this.active = true;
        this.lastSeenAt = seenAt;
    }

    public void markStartDelivered(String gameKey, OffsetDateTime seenAt) {
        this.lastStartedGameKey = gameKey;
        this.lastSeenAt = seenAt;
    }

    public void disable(OffsetDateTime seenAt) {
        this.active = false;
        this.lastSeenAt = seenAt;
    }
}
