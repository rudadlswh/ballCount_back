package com.kbo.crawlerapi.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "line_scores")
public class LineScore {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(name = "inning_number", nullable = false)
    private int inningNumber;

    @Column(name = "away_runs")
    private Integer awayRuns;

    @Column(name = "home_runs")
    private Integer homeRuns;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected LineScore() {
    }

    public LineScore(UUID id, Game game, int inningNumber, Integer awayRuns, Integer homeRuns) {
        this.id = id;
        this.game = game;
        this.inningNumber = inningNumber;
        this.awayRuns = awayRuns;
        this.homeRuns = homeRuns;
    }

    public int getInningNumber() {
        return inningNumber;
    }

    public Integer getAwayRuns() {
        return awayRuns;
    }

    public Integer getHomeRuns() {
        return homeRuns;
    }

    public void updateRuns(Integer awayRuns, Integer homeRuns) {
        this.awayRuns = awayRuns;
        this.homeRuns = homeRuns;
    }
}
