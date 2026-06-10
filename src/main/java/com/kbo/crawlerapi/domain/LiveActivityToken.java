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
@Table(name = "live_activity_tokens")
public class LiveActivityToken {

    @Id
    private UUID id;

    @Column(name = "activity_id", nullable = false, length = 120)
    private String activityId;

    @Column(nullable = false, length = 20)
    private String platform;

    @Column(nullable = false, length = 30)
    private String environment;

    @Column(name = "activity_token", nullable = false)
    private String activityToken;

    @Column(name = "installation_id", length = 100)
    private String installationId;

    @Column(name = "favorite_team_id", length = 30)
    private String favoriteTeamId;

    @Column(name = "public_game_id", length = 80)
    private String publicGameId;

    @Column(name = "provider_game_id", length = 100)
    private String providerGameId;

    @Column(name = "database_id", length = 80)
    private String databaseId;

    @Column(name = "stable_detail_identity", length = 180)
    private String stableDetailIdentity;

    @Column(name = "content_state_hash", length = 128)
    private String contentStateHash;

    @Column(name = "content_state_json", columnDefinition = "TEXT")
    private String contentStateJson;

    @Column(name = "active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    protected LiveActivityToken() {
    }

    public LiveActivityToken(
            UUID id,
            String activityId,
            String platform,
            String environment,
            String activityToken,
            String installationId,
            String favoriteTeamId,
            String publicGameId,
            String providerGameId,
            String databaseId,
            String stableDetailIdentity,
            OffsetDateTime lastSeenAt
    ) {
        this.id = id;
        this.activityId = activityId;
        this.platform = platform;
        this.environment = environment;
        this.activityToken = activityToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.publicGameId = publicGameId;
        this.providerGameId = providerGameId;
        this.databaseId = databaseId;
        this.stableDetailIdentity = stableDetailIdentity;
        this.active = true;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public String getActivityId() {
        return activityId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getActivityToken() {
        return activityToken;
    }

    public String getInstallationId() {
        return installationId;
    }

    public String getFavoriteTeamId() {
        return favoriteTeamId;
    }

    public String getPublicGameId() {
        return publicGameId;
    }

    public String getProviderGameId() {
        return providerGameId;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public String getStableDetailIdentity() {
        return stableDetailIdentity;
    }

    public String getContentStateHash() {
        return contentStateHash;
    }

    public String getContentStateJson() {
        return contentStateJson;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void update(
            String activityId,
            String platform,
            String environment,
            String activityToken,
            String installationId,
            String favoriteTeamId,
            String publicGameId,
            String providerGameId,
            String databaseId,
            String stableDetailIdentity,
            OffsetDateTime seenAt
    ) {
        this.activityId = activityId;
        this.platform = platform;
        this.environment = environment;
        this.activityToken = activityToken;
        this.installationId = installationId;
        this.favoriteTeamId = favoriteTeamId;
        this.publicGameId = publicGameId;
        this.providerGameId = providerGameId;
        this.databaseId = databaseId;
        this.stableDetailIdentity = stableDetailIdentity;
        this.active = true;
        this.lastSeenAt = seenAt;
    }

    public void disable(OffsetDateTime seenAt) {
        this.active = false;
        this.lastSeenAt = seenAt;
    }

    public void markContentStateDelivered(String contentStateHash, String contentStateJson, OffsetDateTime seenAt) {
        this.contentStateHash = contentStateHash;
        this.contentStateJson = contentStateJson;
        this.lastSeenAt = seenAt;
    }
}
