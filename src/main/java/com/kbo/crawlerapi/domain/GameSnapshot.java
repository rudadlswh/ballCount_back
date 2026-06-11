package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "game_snapshots")
public class GameSnapshot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column
    private Integer inning;

    @Column(name = "inning_half", length = 10)
    private String inningHalf;

    @Column(name = "inning_label", length = 50)
    private String inningLabel;

    @Column
    private Integer balls;

    @Column
    private Integer strikes;

    @Column
    private Integer outs;

    @Column(name = "runner_on_first", nullable = false)
    private boolean runnerOnFirst;

    @Column(name = "runner_on_second", nullable = false)
    private boolean runnerOnSecond;

    @Column(name = "runner_on_third", nullable = false)
    private boolean runnerOnThird;

    @Column(name = "first_base_runner_name", length = 100)
    private String firstBaseRunnerName;

    @Column(name = "second_base_runner_name", length = 100)
    private String secondBaseRunnerName;

    @Column(name = "third_base_runner_name", length = 100)
    private String thirdBaseRunnerName;

    @Column(name = "first_base_runner_id", length = 100)
    private String firstBaseRunnerId;

    @Column(name = "second_base_runner_id", length = 100)
    private String secondBaseRunnerId;

    @Column(name = "third_base_runner_id", length = 100)
    private String thirdBaseRunnerId;

    @Column(name = "current_pitcher_name", length = 100)
    private String currentPitcherName;

    @Column(name = "current_batter_name", length = 100)
    private String currentBatterName;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(name = "home_hits")
    private Integer homeHits;

    @Column(name = "away_hits")
    private Integer awayHits;

    @Column(name = "home_errors")
    private Integer homeErrors;

    @Column(name = "away_errors")
    private Integer awayErrors;

    @Column(name = "home_balls")
    private Integer homeBalls;

    @Column(name = "away_balls")
    private Integer awayBalls;

    @Column(name = "raw_hash", length = 128)
    private String rawHash;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    public String getLastCompletedInning() {
        return lastCompletedInning;
    }

    public void setLastCompletedInning(String lastCompletedInning) {
        this.lastCompletedInning = lastCompletedInning;
    }

    @Column(name = "last_completed_inning")
    private String lastCompletedInning;

    public String getLastCompletedBatterName() {
        return lastCompletedBatterName;
    }

    public void setLastCompletedBatterName(String lastCompletedBatterName) {
        this.lastCompletedBatterName = lastCompletedBatterName;
    }

    @Column(name = "last_completed_batter_name")
    private String lastCompletedBatterName;

    public String getLastCompletedPitcherName() {
        return lastCompletedPitcherName;
    }

    public void setLastCompletedPitcherName(String lastCompletedPitcherName) {
        this.lastCompletedPitcherName = lastCompletedPitcherName;
    }

    @Column(name = "last_completed_pitcher_name")
    private String lastCompletedPitcherName;

    public String getLastCompletedPlayResult() {
        return lastCompletedPlayResult;
    }

    public void setLastCompletedPlayResult(String lastCompletedPlayResult) {
        this.lastCompletedPlayResult = lastCompletedPlayResult;
    }

    @Column(name = "last_completed_play_result")
    private String lastCompletedPlayResult;

    public String getLastCompletedPlayKey() {
        return lastCompletedPlayKey;
    }

    public void setLastCompletedPlayKey(String lastCompletedPlayKey) {
        this.lastCompletedPlayKey = lastCompletedPlayKey;
    }

    @Column(name = "last_completed_play_key")
    private String lastCompletedPlayKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected GameSnapshot() {
    }

    public GameSnapshot(
            UUID id,
            Game game,
            Integer inning,
            String inningHalf,
            String inningLabel,
            Integer balls,
            Integer strikes,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            String currentPitcherName,
            String currentBatterName,
            Integer homeScore,
            Integer awayScore,
            Integer homeHits,
            Integer awayHits,
            Integer homeErrors,
            Integer awayErrors,
            Integer homeBalls,
            Integer awayBalls,
            String rawHash,
            String lastCompletedInning,
            OffsetDateTime fetchedAt
    ) {
        this(
                id,
                game,
                inning,
                inningHalf,
                inningLabel,
                balls,
                strikes,
                outs,
                runnerOnFirst,
                runnerOnSecond,
                runnerOnThird,
                null,
                null,
                null,
                null,
                null,
                null,
                currentPitcherName,
                currentBatterName,
                homeScore,
                awayScore,
                homeHits,
                awayHits,
                homeErrors,
                awayErrors,
                homeBalls,
                awayBalls,
                rawHash,
                lastCompletedInning,
                null,
                null,
                null,
                null,
                null,
                fetchedAt
        );
    }

    public GameSnapshot(
            UUID id,
            Game game,
            Integer inning,
            String inningHalf,
            String inningLabel,
            Integer balls,
            Integer strikes,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            String currentPitcherName,
            String currentBatterName,
            Integer homeScore,
            Integer awayScore,
            Integer homeHits,
            Integer awayHits,
            Integer homeErrors,
            Integer awayErrors,
            Integer homeBalls,
            Integer awayBalls,
            String rawHash,
            String lastCompletedInning,
            String lastCompletedBatterName,
            String lastCompletedPitcherName,
            String lastCompletedPlayResult,
            String lastCompletedPlayKey,
            OffsetDateTime sourceUpdatedAt,
            OffsetDateTime fetchedAt
    ) {
        this(
                id,
                game,
                inning,
                inningHalf,
                inningLabel,
                balls,
                strikes,
                outs,
                runnerOnFirst,
                runnerOnSecond,
                runnerOnThird,
                null,
                null,
                null,
                null,
                null,
                null,
                currentPitcherName,
                currentBatterName,
                homeScore,
                awayScore,
                homeHits,
                awayHits,
                homeErrors,
                awayErrors,
                homeBalls,
                awayBalls,
                rawHash,
                lastCompletedInning,
                lastCompletedBatterName,
                lastCompletedPitcherName,
                lastCompletedPlayResult,
                lastCompletedPlayKey,
                sourceUpdatedAt,
                fetchedAt
        );
    }

    public GameSnapshot(
            UUID id,
            Game game,
            Integer inning,
            String inningHalf,
            String inningLabel,
            Integer balls,
            Integer strikes,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            String firstBaseRunnerName,
            String secondBaseRunnerName,
            String thirdBaseRunnerName,
            String firstBaseRunnerId,
            String secondBaseRunnerId,
            String thirdBaseRunnerId,
            String currentPitcherName,
            String currentBatterName,
            Integer homeScore,
            Integer awayScore,
            Integer homeHits,
            Integer awayHits,
            Integer homeErrors,
            Integer awayErrors,
            Integer homeBalls,
            Integer awayBalls,
            String rawHash,
            String lastCompletedInning,
            String lastCompletedBatterName,
            String lastCompletedPitcherName,
            String lastCompletedPlayResult,
            String lastCompletedPlayKey,
            OffsetDateTime sourceUpdatedAt,
            OffsetDateTime fetchedAt
    ) {
        this.id = id;
        this.game = game;
        this.inning = inning;
        this.inningHalf = inningHalf;
        this.inningLabel = inningLabel;
        this.balls = balls;
        this.strikes = strikes;
        this.outs = outs;
        this.runnerOnFirst = runnerOnFirst;
        this.runnerOnSecond = runnerOnSecond;
        this.runnerOnThird = runnerOnThird;
        this.firstBaseRunnerName = runnerOnFirst ? clean(firstBaseRunnerName) : null;
        this.secondBaseRunnerName = runnerOnSecond ? clean(secondBaseRunnerName) : null;
        this.thirdBaseRunnerName = runnerOnThird ? clean(thirdBaseRunnerName) : null;
        this.firstBaseRunnerId = runnerOnFirst ? clean(firstBaseRunnerId) : null;
        this.secondBaseRunnerId = runnerOnSecond ? clean(secondBaseRunnerId) : null;
        this.thirdBaseRunnerId = runnerOnThird ? clean(thirdBaseRunnerId) : null;
        this.currentPitcherName = currentPitcherName;
        this.currentBatterName = currentBatterName;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.homeHits = homeHits;
        this.awayHits = awayHits;
        this.homeErrors = homeErrors;
        this.awayErrors = awayErrors;
        this.homeBalls = homeBalls;
        this.awayBalls = awayBalls;
        this.rawHash = rawHash;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.fetchedAt = fetchedAt;
        this.lastCompletedInning = lastCompletedInning;
        this.lastCompletedBatterName = lastCompletedBatterName;
        this.lastCompletedPitcherName = lastCompletedPitcherName;
        this.lastCompletedPlayResult = lastCompletedPlayResult;
        this.lastCompletedPlayKey = lastCompletedPlayKey;       
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public Integer getInning() {
        return inning;
    }

    public Game getGame() {
        return game;
    }

    public String getInningHalf() {
        return inningHalf;
    }

    public String getInningLabel() {
        return inningLabel;
    }

    public Integer getBalls() {
        return balls;
    }

    public Integer getStrikes() {
        return strikes;
    }

    public Integer getOuts() {
        return outs;
    }

    public boolean isRunnerOnFirst() {
        return runnerOnFirst;
    }

    public boolean isRunnerOnSecond() {
        return runnerOnSecond;
    }

    public boolean isRunnerOnThird() {
        return runnerOnThird;
    }

    public String getFirstBaseRunnerName() {
        return firstBaseRunnerName;
    }

    public String getSecondBaseRunnerName() {
        return secondBaseRunnerName;
    }

    public String getThirdBaseRunnerName() {
        return thirdBaseRunnerName;
    }

    public String getFirstBaseRunnerId() {
        return firstBaseRunnerId;
    }

    public String getSecondBaseRunnerId() {
        return secondBaseRunnerId;
    }

    public String getThirdBaseRunnerId() {
        return thirdBaseRunnerId;
    }

    public String getCurrentPitcherName() {
        return currentPitcherName;
    }

    public String getCurrentBatterName() {
        return currentBatterName;
    }

    public Integer getHomeScore() {
        return homeScore;
    }

    public Integer getHomeHits() {
        return homeHits;
    }

    public Integer getAwayHits() {
        return awayHits;
    }

    public Integer getHomeErrors() {
        return homeErrors;
    }

    public Integer getAwayErrors() {
        return awayErrors;
    }

    public Integer getHomeBalls() {
        return homeBalls;
    }

    public Integer getAwayBalls() {
        return awayBalls;
    }

    public Integer getAwayScore() {
        return awayScore;
    }

    public String getRawHash() {
        return rawHash;
    }

    public OffsetDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public OffsetDateTime getFetchedAt() {
        return fetchedAt;
    }
}
