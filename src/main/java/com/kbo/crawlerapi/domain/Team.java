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
@Table(name = "teams")
public class Team {

    @Id
    private UUID id;

    @Column(name = "team_code", nullable = false, unique = true, length = 30)
    private String teamCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "short_name", nullable = false, length = 50)
    private String shortName;

    @Column(name = "english_name", length = 100)
    private String englishName;

    @Column(name = "logo_url")
    private String logoUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Team() {
    }

    public Team(UUID id, String teamCode, String name, String shortName, String englishName, String logoUrl) {
        this.id = id;
        this.teamCode = teamCode;
        this.name = name;
        this.shortName = shortName;
        this.englishName = englishName;
        this.logoUrl = logoUrl;
    }

    public UUID getId() {
        return id;
    }

    public String getTeamCode() {
        return teamCode;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getEnglishName() {
        return englishName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean syncMetadata(String name, String shortName, String englishName, String logoUrl) {
        boolean changed = false;
        if (!this.name.equals(name)) {
            this.name = name;
            changed = true;
        }
        if (!this.shortName.equals(shortName)) {
            this.shortName = shortName;
            changed = true;
        }
        if (!java.util.Objects.equals(this.englishName, englishName)) {
            this.englishName = englishName;
            changed = true;
        }
        if (!java.util.Objects.equals(this.logoUrl, logoUrl)) {
            this.logoUrl = logoUrl;
            changed = true;
        }
        return changed;
    }
}
