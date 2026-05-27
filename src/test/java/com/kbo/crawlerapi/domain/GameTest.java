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
                OffsetDateTime.of(2026, 5, 19, 20, 10, 0, 0, ZoneOffset.ofHours(9))
        );

        assertThat(game.getStatus()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(game.isCancelled()).isFalse();
        assertThat(game.isPostponed()).isFalse();
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
