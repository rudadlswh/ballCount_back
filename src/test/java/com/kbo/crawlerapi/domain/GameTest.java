package com.kbo.crawlerapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameTest {

    @Test
    void unconfirmedFinalCanRecoverToLiveDetailSync() {
        Game game = fixtureGame(GameStatus.FINAL);

        boolean changed = game.syncDetail(
                GameStatus.LIVE,
                6,
                4,
                "Bottom 9",
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 5, 19, 21, 10, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(changed).isTrue();
        assertThat(game.getStatus()).isEqualTo(GameStatus.LIVE);
        assertThat(game.getFinalConfirmedAt()).isNull();
        assertThat(game.getInningState()).isEqualTo("Bottom 9");
    }

    @Test
    void confirmedFinalKeepsTerminalStatusOverLiveDetailSync() {
        Game game = fixtureGame(GameStatus.FINAL);
        game.confirmFinal(OffsetDateTime.of(2026, 5, 19, 21, 10, 0, 0, ZoneOffset.ofHours(9)));

        game.syncDetail(
                GameStatus.LIVE,
                6,
                4,
                "Bottom 9",
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 5, 19, 21, 9, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(game.getStatus()).isEqualTo(GameStatus.FINAL);
        assertThat(game.getFinalConfirmedAt()).isNotNull();
    }

    @Test
    void confirmedFinalCanRecoverToSuspendedDetailSync() {
        Game game = fixtureGame(GameStatus.FINAL);
        game.confirmFinal(OffsetDateTime.of(2026, 5, 19, 21, 10, 0, 0, ZoneOffset.ofHours(9)));

        game.syncDetail(
                GameStatus.SUSPENDED,
                6,
                4,
                "Top 8",
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                "우천중단",
                OffsetDateTime.of(2026, 5, 19, 20, 9, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(game.getStatus()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(game.getFinalConfirmedAt()).isNull();
        assertThat(game.getStatusReason()).isEqualTo("우천중단");
        assertThat(game.isCancelled()).isFalse();
        assertThat(game.isPostponed()).isFalse();
    }


    @Test
    void scheduleSyncDoesNotDowngradeSuspendedToLive() {
        Game game = fixtureGame(GameStatus.SUSPENDED);

        game.syncSchedule(
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getGameDate(),
                game.getScheduledAt(),
                game.getStadium(),
                GameStatus.LIVE,
                game.getHomeTeam(),
                game.getAwayTeam(),
                6,
                4,
                false,
                false,
                null,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 5, 19, 20, 10, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(game.getStatus()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(game.isCancelled()).isFalse();
        assertThat(game.isPostponed()).isFalse();
    }

    @Test
    void scheduleSyncUpdatesStartingPitchersOnlyWhenIncomingNamesArePresent() {
        Game game = fixtureGame(GameStatus.SCHEDULED);

        boolean changed = game.syncSchedule(
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getGameDate(),
                game.getScheduledAt(),
                game.getStadium(),
                GameStatus.SCHEDULED,
                game.getHomeTeam(),
                game.getAwayTeam(),
                null,
                null,
                false,
                false,
                null,
                null,
                "홈선발",
                "원정선발",
                OffsetDateTime.of(2026, 5, 19, 12, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(changed).isTrue();
        assertThat(game.getHomeStartingPitcherName()).isEqualTo("홈선발");
        assertThat(game.getAwayStartingPitcherName()).isEqualTo("원정선발");

        changed = game.syncSchedule(
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getGameDate(),
                game.getScheduledAt(),
                game.getStadium(),
                GameStatus.SCHEDULED,
                game.getHomeTeam(),
                game.getAwayTeam(),
                null,
                null,
                false,
                false,
                null,
                null,
                " ",
                null,
                OffsetDateTime.of(2026, 5, 19, 12, 5, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(changed).isFalse();
        assertThat(game.getHomeStartingPitcherName()).isEqualTo("홈선발");
        assertThat(game.getAwayStartingPitcherName()).isEqualTo("원정선발");
    }

    @Test
    void liveDetailSyncPreservesExistingScoreWhenIncomingScoreRegresses() {
        Game game = fixtureGame(GameStatus.LIVE);

        boolean changed = game.syncDetail(
                GameStatus.LIVE,
                0,
                0,
                "Bottom 7",
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 5, 19, 20, 10, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(changed).isTrue();
        assertThat(game.getHomeScore()).isEqualTo(6);
        assertThat(game.getAwayScore()).isEqualTo(4);
        assertThat(game.getInningState()).isEqualTo("Bottom 7");
    }

    @Test
    void liveScheduleSyncPreservesExistingScoreWhenIncomingScoreRegresses() {
        Game game = fixtureGame(GameStatus.LIVE);

        boolean changed = game.syncSchedule(
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getGameDate(),
                game.getScheduledAt(),
                game.getStadium(),
                GameStatus.LIVE,
                game.getHomeTeam(),
                game.getAwayTeam(),
                0,
                0,
                false,
                false,
                null,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 5, 19, 20, 10, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(changed).isFalse();
        assertThat(game.getHomeScore()).isEqualTo(6);
        assertThat(game.getAwayScore()).isEqualTo(4);
    }

    private Game fixtureGame(GameStatus status) {
        Team homeTeam = new Team(UUID.randomUUID(), "lotte", "롯데 자이언츠", "롯데", "Lotte Giants", null);
        Team awayTeam = new Team(UUID.randomUUID(), "hanwha", "한화 이글스", "한화", "Hanwha Eagles", null);
        return new Game(
                UUID.randomUUID(),
                "20260519-HAN-LOT",
                "kbo",
                "20260519LTHH0",
                LocalDate.of(2026, 5, 19),
                OffsetDateTime.of(2026, 5, 19, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "사직",
                status,
                homeTeam,
                awayTeam,
                6,
                4,
                "경기 종료",
                false,
                false,
                null,
                null,
                null
        );
    }
}
