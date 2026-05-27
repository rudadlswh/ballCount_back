package com.kbo.crawlerapi.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "games")
public class Game {

    @Id
    private UUID id;

    @Column(name = "public_game_id", nullable = false, unique = true, length = 50)
    private String publicGameId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_game_id", length = 100)
    private String providerGameId;

    @Column(name = "game_date", nullable = false)
    private LocalDate gameDate;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(length = 100)
    private String stadium;

    @Convert(converter = GameStatusConverter.class)
    @Column(nullable = false, length = 30)
    private GameStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_team_id", nullable = false)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "away_team_id", nullable = false)
    private Team awayTeam;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(name = "inning_state", length = 50)
    private String inningState;

    @Column(name = "is_cancelled", nullable = false)
    private boolean isCancelled;

    @Column(name = "is_postponed", nullable = false)
    private boolean isPostponed;

    @Convert(converter = GameCancelReasonConverter.class)
    @Column(name = "cancel_reason", length = 30)
    private GameCancelReason cancelReason;

    @Column(name = "raw_cancel_text")
    private String rawCancelText;

    @Column(name = "home_starting_pitcher_name", length = 100)
    private String homeStartingPitcherName;

    @Column(name = "away_starting_pitcher_name", length = 100)
    private String awayStartingPitcherName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lineup_data", columnDefinition = "jsonb")
    private String lineupData;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "final_confirmed_at")
    private OffsetDateTime finalConfirmedAt;

    @Column(name = "live_last_checked_at")
    private OffsetDateTime liveLastCheckedAt;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Game() {
    }

    public Game(
            UUID id,
            String publicGameId,
            String provider,
            String providerGameId,
            LocalDate gameDate,
            OffsetDateTime scheduledAt,
            String stadium,
            GameStatus status,
            Team homeTeam,
            Team awayTeam,
            Integer homeScore,
            Integer awayScore,
            String inningState,
            boolean isCancelled,
            boolean isPostponed,
            GameCancelReason cancelReason,
            String rawCancelText,
            OffsetDateTime sourceUpdatedAt
    ) {
        this.id = id;
        this.publicGameId = publicGameId;
        this.provider = provider;
        this.providerGameId = providerGameId;
        this.gameDate = gameDate;
        this.scheduledAt = scheduledAt;
        this.stadium = stadium;
        this.status = status;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.inningState = inningState;
        this.isCancelled = isCancelled;
        this.isPostponed = isPostponed;
        this.cancelReason = cancelReason;
        this.rawCancelText = rawCancelText;
        this.sourceUpdatedAt = sourceUpdatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicGameId() {
        return publicGameId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderGameId() {
        return providerGameId;
    }

    public LocalDate getGameDate() {
        return gameDate;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public String getStadium() {
        return stadium;
    }

    public GameStatus getStatus() {
        return status;
    }

    public Team getHomeTeam() {
        return homeTeam;
    }

    public Team getAwayTeam() {
        return awayTeam;
    }

    public Integer getHomeScore() {
        return homeScore;
    }

    public Integer getAwayScore() {
        return awayScore;
    }

    public String getInningState() {
        return inningState;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public boolean isPostponed() {
        return isPostponed;
    }

    public GameCancelReason getCancelReason() {
        return cancelReason;
    }

    public String getRawCancelText() {
        return rawCancelText;
    }

    public String getHomeStartingPitcherName() {
        return homeStartingPitcherName;
    }

    public String getAwayStartingPitcherName() {
        return awayStartingPitcherName;
    }

    public String getLineupData() {
        return lineupData;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public OffsetDateTime getFinalConfirmedAt() {
        return finalConfirmedAt;
    }

    public OffsetDateTime getLiveLastCheckedAt() {
        return liveLastCheckedAt;
    }

    public OffsetDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean syncSchedule(
            String publicGameId,
            String providerGameId,
            LocalDate gameDate,
            OffsetDateTime scheduledAt,
            String stadium,
            GameStatus status,
            Team homeTeam,
            Team awayTeam,
            Integer homeScore,
            Integer awayScore,
            boolean isCancelled,
            boolean isPostponed,
            GameCancelReason cancelReason,
            String rawCancelText,
            OffsetDateTime sourceUpdatedAt
    ) {
        boolean changed = false;
        if (!this.publicGameId.equals(publicGameId)) {
            this.publicGameId = publicGameId;
            changed = true;
        }
        if (!java.util.Objects.equals(this.providerGameId, providerGameId)) {
            this.providerGameId = providerGameId;
            changed = true;
        }
        if (!this.gameDate.equals(gameDate)) {
            this.gameDate = gameDate;
            changed = true;
        }
        if (!java.util.Objects.equals(this.scheduledAt, scheduledAt)) {
            this.scheduledAt = scheduledAt;
            changed = true;
        }
        if (!java.util.Objects.equals(this.stadium, stadium)) {
            this.stadium = stadium;
            changed = true;
        }
        GameStatus effectiveStatus = shouldKeepSuspendedDuringSchedule(status) ? GameStatus.SUSPENDED : status;
        if (this.status != effectiveStatus) {
            this.status = effectiveStatus;
            changed = true;
        }
        if (!this.homeTeam.getId().equals(homeTeam.getId())) {
            this.homeTeam = homeTeam;
            changed = true;
        }
        if (!this.awayTeam.getId().equals(awayTeam.getId())) {
            this.awayTeam = awayTeam;
            changed = true;
        }
        if (!java.util.Objects.equals(this.homeScore, homeScore)) {
            this.homeScore = homeScore;
            changed = true;
        }
        if (!java.util.Objects.equals(this.awayScore, awayScore)) {
            this.awayScore = awayScore;
            changed = true;
        }
        if (this.isCancelled != isCancelled) {
            this.isCancelled = isCancelled;
            changed = true;
        }
        if (this.isPostponed != isPostponed) {
            this.isPostponed = isPostponed;
            changed = true;
        }
        if (this.cancelReason != cancelReason) {
            this.cancelReason = cancelReason;
            changed = true;
        }
        if (!java.util.Objects.equals(this.rawCancelText, rawCancelText)) {
            this.rawCancelText = rawCancelText;
            changed = true;
        }
        if (changed && !java.util.Objects.equals(this.sourceUpdatedAt, sourceUpdatedAt)) {
            this.sourceUpdatedAt = sourceUpdatedAt;
        }
        return changed;
    }

    public boolean syncDetail(
            GameStatus status,
            Integer homeScore,
            Integer awayScore,
            String inningState,
            boolean isCancelled,
            boolean isPostponed,
            GameCancelReason cancelReason,
            String rawCancelText,
            String homeStartingPitcherName,
            String awayStartingPitcherName,
            String lineupData,
            String statusReason,
            OffsetDateTime sourceUpdatedAt
    ) {
        boolean changed = false;
        GameStatus effectiveStatus = shouldKeepFinalStatus(status) ? GameStatus.FINAL : status;
        if (this.status != effectiveStatus) {
            this.status = effectiveStatus;
            changed = true;
        }
        if (effectiveStatus != GameStatus.FINAL && this.finalConfirmedAt != null) {
            this.finalConfirmedAt = null;
            changed = true;
        }
        if (homeScore != null && !java.util.Objects.equals(this.homeScore, homeScore)) {
            this.homeScore = homeScore;
            changed = true;
        }
        if (awayScore != null && !java.util.Objects.equals(this.awayScore, awayScore)) {
            this.awayScore = awayScore;
            changed = true;
        }
        if (hasText(inningState) && !java.util.Objects.equals(this.inningState, inningState)) {
            this.inningState = inningState;
            changed = true;
        }
        if (this.isCancelled != isCancelled) {
            this.isCancelled = isCancelled;
            changed = true;
        }
        if (this.isPostponed != isPostponed) {
            this.isPostponed = isPostponed;
            changed = true;
        }
        if (this.cancelReason != cancelReason) {
            this.cancelReason = cancelReason;
            changed = true;
        }
        if (!java.util.Objects.equals(this.rawCancelText, rawCancelText)) {
            this.rawCancelText = rawCancelText;
            changed = true;
        }
        if (hasText(homeStartingPitcherName) && !java.util.Objects.equals(this.homeStartingPitcherName, homeStartingPitcherName)) {
            this.homeStartingPitcherName = homeStartingPitcherName;
            changed = true;
        }
        if (hasText(awayStartingPitcherName) && !java.util.Objects.equals(this.awayStartingPitcherName, awayStartingPitcherName)) {
            this.awayStartingPitcherName = awayStartingPitcherName;
            changed = true;
        }
        if (hasText(lineupData) && !java.util.Objects.equals(this.lineupData, lineupData)) {
            this.lineupData = lineupData;
            changed = true;
        }
        if (hasText(statusReason) && !java.util.Objects.equals(this.statusReason, statusReason)) {
            this.statusReason = statusReason;
            changed = true;
        }
        if (!java.util.Objects.equals(this.sourceUpdatedAt, sourceUpdatedAt)) {
            this.sourceUpdatedAt = sourceUpdatedAt;
            changed = true;
        }
        return changed;
    }

    public void markLiveChecked(OffsetDateTime checkedAt) {
        this.liveLastCheckedAt = checkedAt;
    }

    public boolean confirmFinal(OffsetDateTime confirmedAt) {
        if (this.finalConfirmedAt != null) {
            return false;
        }
        this.finalConfirmedAt = confirmedAt;
        return true;
    }

    private boolean shouldKeepFinalStatus(GameStatus incomingStatus) {
        return this.status == GameStatus.FINAL
                && this.finalConfirmedAt != null
                && incomingStatus != GameStatus.FINAL
                && incomingStatus != GameStatus.CANCELLED
                && incomingStatus != GameStatus.POSTPONED
                && incomingStatus != GameStatus.SUSPENDED;
    }

    private boolean shouldKeepSuspendedDuringSchedule(GameStatus incomingStatus) {
        return this.status == GameStatus.SUSPENDED
                && incomingStatus == GameStatus.LIVE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
