package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameStreamRegistry;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameLiveStreamControllerTest {

    @Test
    void subscribesProviderGameIdWithResolvedPublicGameId() {
        GameRepository gameRepository = mock(GameRepository.class);
        LiveGameStreamRegistry streamRegistry = new LiveGameStreamRegistry();
        GameLiveStreamController controller = new GameLiveStreamController(gameRepository, streamRegistry);
        Game game = game();

        when(gameRepository.findByPublicGameId("20260707SKOB0")).thenReturn(Optional.empty());
        when(gameRepository.findByProviderAndProviderGameId("kbo", "20260707SKOB0")).thenReturn(Optional.of(game));

        controller.streamGame("20260707SKOB0");

        assertThat(streamRegistry.subscriberCount("20260707-DOO-SSG")).isEqualTo(1);
        assertThat(streamRegistry.subscriberCount("20260707SKOB0")).isZero();
    }

    private Game game() {
        Team homeTeam = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "ssg",
                "SSG Landers",
                "SSG",
                "SSG Landers",
                null
        );
        Team awayTeam = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "doosan",
                "Doosan Bears",
                "두산",
                "Doosan Bears",
                null
        );
        return new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260707-DOO-SSG",
                "kbo",
                "20260707SKOB0",
                LocalDate.of(2026, 7, 7),
                OffsetDateTime.of(2026, 7, 7, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "문학",
                GameStatus.LIVE,
                homeTeam,
                awayTeam,
                0,
                0,
                "1회초",
                false,
                false,
                null,
                null,
                null
        );
    }
}
