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
            Integer homeScore,
            Integer awayScore,
            Integer homeHits,
            Integer awayHits,
            Integer homeErrors,
            Integer awayErrors,
            Integer homeBalls,
            Integer awayBalls,
            String rawHash,
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
    }

    public Integer getInning() {
        return inning;
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
