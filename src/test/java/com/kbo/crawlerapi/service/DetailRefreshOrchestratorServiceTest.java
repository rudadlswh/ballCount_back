package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kbo.crawlerapi.config.SchedulerShellProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@ExtendWith(MockitoExtension.class)
class DetailRefreshOrchestratorServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-09T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Mock
    private LineScoreRepository lineScoreRepository;

    private DetailRefreshOrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        orchestratorService = new DetailRefreshOrchestratorService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                new StubGameDetailImportService(),
                FIXED_CLOCK,
                new SchedulerShellProperties()
        );
    }

    @Test
    void selectsScheduledGameForPregameRefreshAndStopsFinalCompleteGame() {
        Game scheduledGame = fixtureGame(
                "20260409-DOO-KIW",
                "20260409WOOB0",
                LocalDate.of(2026, 4, 9),
                GameStatus.SCHEDULED,
                null,
                null
        );
        Game finalGame = fixtureGame(
                "20260409-LG-KIA",
                "20260409HTLG0",
                LocalDate.of(2026, 4, 9),
                GameStatus.FINAL,
                7,
                2
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 9))))
                .thenReturn(List.of(scheduledGame, finalGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(scheduledGame.getId())))
                .thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(finalGame.getId())))
                .thenReturn(Optional.of(new GameSnapshot(
                        UUID.randomUUID(),
                        finalGame,
                        9,
                        "top",
                        "Top 9",
                        0,
                        0,
                        3,
                        false,
                        false,
                        false,
                        null,
                        null,
                        7,
                        2,
                        8,
                        7,
                        0,
                        1,
                        10,
                        3,
                        "hash",
                        null,
                        OffsetDateTime.of(2026, 4, 9, 20, 0, 0, 0, ZoneOffset.ofHours(9))
                )));
        when(lineScoreRepository.countByGame_Id(eq(scheduledGame.getId()))).thenReturn(0L);
        when(lineScoreRepository.countByGame_Id(eq(finalGame.getId()))).thenReturn(9L);

        var result = orchestratorService.runPass(LocalDate.of(2026, 4, 9), false);

        assertThat(result.totalGames()).isEqualTo(2);
        assertThat(result.selectedGameCount()).isEqualTo(1);
        assertThat(result.decisions()).hasSize(2);
        assertThat(result.decisions().get(0).gameId()).isEqualTo("20260409-DOO-KIW");
        assertThat(result.decisions().get(0).selected()).isTrue();
        assertThat(result.decisions().get(0).phase()).isEqualTo("pregame");
        assertThat(result.decisions().get(0).refreshIntervalSeconds()).isEqualTo(1800);
        assertThat(result.decisions().get(1).gameId()).isEqualTo("20260409-LG-KIA");
        assertThat(result.decisions().get(1).selected()).isFalse();
        assertThat(result.decisions().get(1).phase()).isEqualTo("stopped");
        assertThat(result.decisions().get(1).finalDataComplete()).isTrue();
    }

    @Test
    void returnsEmptyDecisionSetWhenNoGamesExist() {
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 10))))
                .thenReturn(List.of());

        var result = orchestratorService.runPass(LocalDate.of(2026, 4, 10), false);

        assertThat(result.totalGames()).isZero();
        assertThat(result.selectedGameCount()).isZero();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.executionResults()).isEmpty();
    }

    private Game fixtureGame(
            String publicGameId,
            String providerGameId,
            LocalDate gameDate,
            GameStatus status,
            Integer homeScore,
            Integer awayScore
    ) {
        Team homeTeam = new Team(UUID.randomUUID(), "lg", "LG Twins", "LG", "LG Twins", null);
        Team awayTeam = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        return new Game(
                UUID.randomUUID(),
                publicGameId,
                "kbo",
                providerGameId,
                gameDate,
                OffsetDateTime.of(gameDate.getYear(), gameDate.getMonthValue(), gameDate.getDayOfMonth(), 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                status,
                homeTeam,
                awayTeam,
                homeScore,
                awayScore,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private static final class StubGameDetailImportService extends GameDetailImportService {

        private StubGameDetailImportService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }
    }
}
